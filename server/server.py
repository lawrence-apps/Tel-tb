# Telegram group -> TV stream server (bot login, koi user login nahi)
#
# Chalane ka tareeka (PC ya phone/Termux, TV wale WiFi par):
#   pip install -r requirements.txt
#   .env.example ko .env banao, values bharo
#   python server.py
#
# Bot group me admin hona chahiye. Group me jo video/music/photo daloge,
# wo apne aap list me aa jayega. Admin bot ko /scan bheje to poora group dobara padhega.

import os, re, json, asyncio, html
from pathlib import Path
from aiohttp import web
from telethon import TelegramClient, events, utils, types

HERE = Path(__file__).parent


def load_env():
    f = HERE / ".env"
    if f.exists():
        for line in f.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip())


load_env()
API_ID = int(os.environ["API_ID"])
API_HASH = os.environ["API_HASH"]
BOT_TOKEN = os.environ["BOT_TOKEN"]
GROUP_ID = int(os.environ["GROUP_ID"])
ADMIN_ID = int(os.environ.get("ADMIN_ID", "0") or 0)
PORT = int(os.environ.get("PORT", "8080"))

CHUNK = 512 * 1024  # Telegram max chunk
VIDEO_EXT = (".mkv", ".mp4", ".avi", ".webm", ".ts", ".mov", ".m4v", ".flv")
AUDIO_EXT = (".mp3", ".m4a", ".flac", ".ogg", ".opus", ".wav", ".aac")
INDEX_FILE = HERE / "index.json"

client: TelegramClient = None
group = None
index = {}  # msg_id -> {id, kind, title, size}


def save_index():
    INDEX_FILE.write_text(json.dumps(index, ensure_ascii=False))


def load_index():
    global index
    if INDEX_FILE.exists():
        index = {int(k): v for k, v in json.loads(INDEX_FILE.read_text()).items()}


def kind(m):
    if not m or not m.media:
        return None
    if m.photo:
        return "photo"
    if not m.document or not m.file:
        return None
    mime = (m.file.mime_type or "").lower()
    ext = (m.file.ext or "").lower()
    name = (m.file.name or "").lower()
    if mime.startswith("video") or ext in VIDEO_EXT or name.endswith(VIDEO_EXT):
        return "video"
    if mime.startswith("audio") or ext in AUDIO_EXT or name.endswith(AUDIO_EXT):
        return "audio"
    if mime.startswith("image"):
        return "photo"
    return None


def title(m):
    if m.file and m.file.name:
        return m.file.name
    text = (m.message or "").strip().splitlines()
    return (text[0][:80] if text else "") or f"{kind(m)}_{m.id}"


def add(m):
    k = kind(m)
    if k:
        index[m.id] = {"id": m.id, "kind": k, "title": title(m),
                       "size": m.file.size if m.file else 0}
        return True
    return False


async def scan():
    """Bot history nahi padh sakta, isliye message ids 1,2,3... batch me maangte hain."""
    found, start, empty = 0, 1, 0
    while empty < 5:  # lagatar 5 khaali batch = group khatam
        ids = list(range(start, start + 100))
        msgs = await client.get_messages(group, ids=ids)
        got = [m for m in msgs if m]
        empty = 0 if got else empty + 1
        for m in got:
            if add(m):
                found += 1
        start += 100
    save_index()
    print(f"Scan poora: {len(index)} files")
    return len(index)


async def get_msg(mid):
    m = await client.get_messages(group, ids=mid)
    if not m or not m.media:
        raise web.HTTPNotFound()
    return m


PARALLEL = int(os.environ.get("PARALLEL", "4"))  # ek saath kitne hisse download hon


async def parallel_chunks(doc, offset, size):
    """Telegram se PARALLEL hisse ek saath maango, order me lautao. Buffering kam hoti hai."""
    total = -(-(size - offset) // CHUNK)  # kitne chunk bache
    n = max(1, min(PARALLEL, total))
    queues = [asyncio.Queue(maxsize=3) for _ in range(n)]

    async def worker(i):
        try:
            async for c in client.iter_download(doc, offset=offset + i * CHUNK,
                                                stride=n * CHUNK, request_size=CHUNK,
                                                limit=-(-(total - i) // n), file_size=size):
                await queues[i].put(bytes(c))
        except Exception as e:
            print("download error:", e)
        finally:
            await queues[i].put(None)

    tasks = [asyncio.create_task(worker(i)) for i in range(n)]
    try:
        k = 0
        while True:
            c = await queues[k % n].get()
            if c is None:
                break
            yield c
            k += 1
    finally:
        for t in tasks:
            t.cancel()


# ---------------- HTTP ----------------

def sorted_items():
    return sorted(index.values(), key=lambda x: x["id"], reverse=True)


async def api_list(request):
    return web.json_response(sorted_items())


async def playlist(request):
    lines = ["#EXTM3U"]
    for it in sorted_items():
        if it["kind"] in ("video", "audio"):
            lines += [f"#EXTINF:-1,{it['title']}", f"http://{request.host}/f/{it['id']}"]
    return web.Response(text="\n".join(lines), content_type="audio/x-mpegurl")


async def home(request):
    rows = []
    for it in sorted_items():
        t = html.escape(it["title"])
        if it["kind"] == "photo":
            rows.append(f'<a href="/f/{it["id"]}"><img src="/f/{it["id"]}" width="280" loading="lazy"></a>')
        else:
            rows.append(f'<p><a href="/f/{it["id"]}">[{it["kind"]}] {t}</a></p>')
    body = ("<meta name=viewport content='width=device-width'><h2>Telegram TV</h2>"
            "<p><a href='/tv.m3u'>VLC playlist</a></p>" + "".join(rows))
    return web.Response(text=body, content_type="text/html")


async def stream(request):
    m = await get_msg(int(request.match_info["id"]))

    if m.photo:
        data = await client.download_media(m, file=bytes)
        return web.Response(body=data, content_type="image/jpeg")

    size = m.file.size
    start, end = 0, size - 1
    rng = request.headers.get("Range")
    if rng:
        r = re.match(r"bytes=(\d*)-(\d*)", rng)
        if r and r.group(1):
            start = int(r.group(1))
            if r.group(2):
                end = min(int(r.group(2)), size - 1)
        elif r and r.group(2):
            start = max(size - int(r.group(2)), 0)
    if start >= size:
        return web.Response(status=416, headers={"Content-Range": f"bytes */{size}"})
    length = end - start + 1

    headers = {
        "Content-Type": m.file.mime_type or "application/octet-stream",
        "Accept-Ranges": "bytes",
        "Content-Length": str(length),
    }
    if rng:
        headers["Content-Range"] = f"bytes {start}-{end}/{size}"
    resp = web.StreamResponse(status=206 if rng else 200, headers=headers)
    await resp.prepare(request)
    if request.method == "HEAD":
        return resp

    aligned = start - (start % CHUNK)
    skip = start - aligned
    remaining = length
    try:
        async for chunk in parallel_chunks(m.document, aligned, size):
            if skip:
                chunk, skip = chunk[skip:], 0
            chunk = chunk[:remaining]
            await resp.write(chunk)
            remaining -= len(chunk)
            if remaining <= 0:
                break
    except (ConnectionResetError, asyncio.CancelledError):
        pass  # TV ne seek / stop kiya
    return resp


# ---------------- main ----------------

async def main():
    global client, group
    client = TelegramClient(str(HERE / "tvbot"), API_ID, API_HASH)
    await client.start(bot_token=BOT_TOKEN)
    real_id, peer_type = utils.resolve_id(GROUP_ID)
    try:
        group = await client.get_input_entity(peer_type(real_id))
    except ValueError:
        # bots group ko access_hash 0 se khol sakte hain
        group = types.InputPeerChannel(real_id, 0)

    load_index()

    @client.on(events.NewMessage(chats=GROUP_ID))
    async def on_new(event):
        if add(event.message):
            save_index()
            print("Naya:", index[event.message.id]["title"])

    @client.on(events.MessageDeleted(chats=GROUP_ID))
    async def on_del(event):
        for mid in event.deleted_ids:
            index.pop(mid, None)
        save_index()

    @client.on(events.NewMessage(pattern=r"^/scan"))
    async def on_scan(event):
        if event.sender_id != ADMIN_ID:
            return
        await event.reply("Group scan ho raha hai...")
        n = await scan()
        await event.reply(f"Ho gaya: {n} files TV par dikh rahi hain.")

    @client.on(events.NewMessage(pattern=r"^/(status|id|help)\b"))
    async def on_cmd(event):
        if event.is_channel and not event.is_group:
            return  # broadcast channel me reply spam karegi
        if ADMIN_ID and event.sender_id != ADMIN_ID:
            if event.is_private:
                await event.reply("Ye commands sirf bot admin ke liye hain.")
            return
        cmd = event.pattern_match.group(1)
        if cmd == "id":
            await event.reply(f"chat_id: {event.chat_id}\naapki id: {event.sender_id}")
        elif cmd == "status":
            c = {}
            for v in index.values():
                c[v["kind"]] = c.get(v["kind"], 0) + 1
            await event.reply("Server chal raha hai.\n"
                              f"Videos: {c.get('video', 0)} · Gaane: {c.get('audio', 0)} · Photos: {c.get('photo', 0)}\n"
                              f"Connection: {'online' if client and client.is_connected() else 'offline'}")
        elif cmd == "help":
            await event.reply("Commands (sirf admin):\n/status — abhi ka haal\n/scan — storage dobara padho\n/id — chat id batao")

    if not index:
        await scan()

    app = web.Application()
    app.add_routes([
        web.get("/", home),
        web.get("/api/list", api_list),
        web.get("/tv.m3u", playlist),
        web.get("/f/{id}", stream),
    ])
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", PORT).start()
    print(f"Ready! TV app me server daalo: http://<is-device-ka-IP>:{PORT}")
    await client.run_until_disconnected()


if __name__ == "__main__":
    asyncio.run(main())
