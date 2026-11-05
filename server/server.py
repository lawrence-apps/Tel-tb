# Remote Telegram storage server (PC / VPS / Coolify) — TV app ke Settings > "Server URL" ke liye.
# App ke andar wale tgserver.py jaisa hi API: /api/status /api/list /api/scan /t/{id} /f/{id}
# TV app me Settings > Server me is machine ka URL dalo (jaise http://1.2.3.4:8080),
# phir TV apna Python server nahi chalayega — ye wala chalta rahega.
#
# Chalane ka tareeka (PC ya phone/Termux, TV wale WiFi par):
#   pip install -r requirements.txt
#   .env.example ko .env banao, values bharo   (ya env vars seedha do — Coolify isi tarah karta hai)
#   python server.py
#
# Bot group me admin hona chahiye. Group me jo media daloge, wo apne aap list me aa jayega.
# Admin bot ko /scan bheje to poora group dobara padhega.

import asyncio, base64, json, os, re, time
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
PARALLEL = int(os.environ.get("PARALLEL", "4"))  # ek saath kitne hisse download hon
BOT_DL_LIMIT = 2000 * 1024 * 1024  # Telegram bots 2GB se badi file nahi nikaal sakte
VIDEO_EXT = (".mkv", ".mp4", ".avi", ".webm", ".ts", ".mov", ".m4v", ".flv")
AUDIO_EXT = (".mp3", ".m4a", ".flac", ".ogg", ".opus", ".wav", ".aac")
SUB_EXT = (".srt", ".vtt", ".ass", ".ssa")
PHOTO_EXT = (".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif")
UPD = re.compile(r"^TGTV-UPDATE\s+(\d+)\s*(\S*)")
INDEX_FILE = HERE / "index.json"

state = {"ready": False, "error": "", "scanning": False, "scanned": 0, "version": 0,
         "title": "", "update": None, "speed": 0, "online": True, "channelError": ""}
BOOT_TS = 0.0
_speed = {"t": 0.0, "n": 0, "last": 0.0}
index = {}  # msg_id -> item dict (app ke /api/list ka seedha jawab)
client: TelegramClient = None
group = None


# ---------------- index ----------------

def save_index():
    tmp = HERE / "index.json.tmp"
    tmp.write_text(json.dumps({"_v": 2, "items": index, "update": state["update"],
                               "title": state["title"]}, ensure_ascii=False))
    os.replace(tmp, INDEX_FILE)


def load_index():
    try:
        data = json.loads(INDEX_FILE.read_text())
        if data.get("_v") == 2:
            index.update({int(k): v for k, v in data["items"].items()})
            state["update"] = data.get("update")
            state["title"] = data.get("title", "")
            state["version"] += 1
    except Exception:
        pass


def kind(m):
    if not m or not m.media:
        return None
    if m.photo:
        return "photo"
    if not m.document or not m.file:
        return None
    mime = (m.file.mime_type or "").lower()
    name = (m.file.name or "").lower()
    ext = (m.file.ext or "").lower()
    if name.endswith(SUB_EXT) or mime in ("application/x-subrip", "text/vtt"):
        return "sub"
    if mime.startswith("video") or name.endswith(VIDEO_EXT) or ext in VIDEO_EXT:
        return "video"
    if mime.startswith("audio") or name.endswith(AUDIO_EXT) or ext in AUDIO_EXT:
        return "audio"
    if mime.startswith("image") or name.endswith(PHOTO_EXT):
        return "photo"
    return None


def _first_line(text):
    for line in (text or "").splitlines():
        line = re.sub(r"#[\w]+", "", line).strip()
        if line:
            return line[:140]
    return ""


def _clean(name):
    if not name:
        return ""
    base = name.rsplit(".", 1)[0] if "." in name else name
    base = base.replace("_", " ")
    base = re.sub(r"(?<=\S)\.(?=\S)", " ", base)
    return re.sub(r"\s+", " ", base).strip()


def _mini(m):
    try:
        thumbs = (m.photo.sizes if m.photo else (m.document.thumbs or [])) or []
        for t in thumbs:
            if isinstance(t, types.PhotoStrippedSize):
                return base64.b64encode(utils.stripped_photo_to_jpg(t.bytes)).decode()
    except Exception:
        pass
    return ""


def note_update(m):
    if not m.document or not m.message:
        return False
    mm = UPD.match(m.message.strip())
    if not mm:
        return False
    code = int(mm.group(1))
    cur = state["update"]
    if not cur or cur["code"] <= code:
        state["update"] = {"id": m.id, "code": code, "name": mm.group(2) or mm.group(1),
                           "size": m.file.size if m.file else 0}
    return True


def add(m):
    if m and note_update(m):
        return False
    k = kind(m)
    if not k:
        return False
    f = m.file
    cap = m.message or ""
    fname = (f.name or "") if f else ""
    if k == "photo" and not fname:
        fname = f"photo_{m.id}.jpg"
    title = _first_line(cap)
    perf = ""
    if k == "audio" and f:
        title = title or (f.title or "")
        perf = f.performer or ""
    title = title or _clean(fname) or f"{k.title()} {m.id}"
    reply = 0
    if m.reply_to and getattr(m.reply_to, "reply_to_msg_id", None):
        reply = m.reply_to.reply_to_msg_id
    index[m.id] = {
        "id": m.id, "kind": k, "title": title, "fileName": fname,
        "mime": (f.mime_type or "") if f else "image/jpeg",
        "size": (f.size or 0) if f else 0,
        "big": bool(f and k == "video" and (f.size or 0) > BOT_DL_LIMIT),
        "duration": int(f.duration or 0) if f and k in ("video", "audio") else 0,
        "width": (f.width or 0) if f and k in ("video", "photo") else 0,
        "height": (f.height or 0) if f and k in ("video", "photo") else 0,
        "date": int(m.date.timestamp()) if m.date else 0,
        "performer": perf,
        "tags": sorted(set(t.lower() for t in re.findall(r"#(\w+)", cap))),
        "mini": _mini(m), "replyTo": reply, "caption": cap[:1000],
    }
    state["version"] += 1
    return True


async def scan():
    if state["scanning"]:
        return
    state["scanning"] = True
    try:
        start, empty, found = 1, 0, {}
        while empty < 5:  # lagatar 5 khaali batch = group khatam
            msgs = await client.get_messages(group, ids=list(range(start, start + 100)))
            got = [m for m in msgs if m]
            empty = 0 if got else empty + 1
            for m in got:
                if add(m):
                    found[m.id] = index[m.id]
            start += 100
            state["scanned"] = start - 1
        # jo post channel se hata diye gaye wo list se bhi hatao
        for mid in [k for k in index if k not in found]:
            index.pop(mid, None)
        state["version"] += 1
        state["channelError"] = ""
        save_index()
        print(f"Scan poora: {len(index)} files")
    except Exception as e:
        state["channelError"] = f"{type(e).__name__}: {e}"
    finally:
        state["scanning"] = False


# ---------------- telegram media ----------------

msg_cache = {}


async def get_msg(mid):
    c = msg_cache.get(mid)
    if c and time.time() - c[0] < 1800:
        return c[1]
    m = await client.get_messages(group, ids=mid)
    if m:
        msg_cache[mid] = (time.time(), m)
        if len(msg_cache) > 500:
            msg_cache.clear()
    return m


async def parallel_chunks(doc, offset, size):
    """Telegram se PARALLEL hisse ek saath maango, order me lautao. Buffering kam hoti hai."""
    total = -(-(size - offset) // CHUNK)
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


def _count(n):
    now = time.time()
    if now - _speed["t"] >= 1.0:
        if _speed["t"]:
            inst = _speed["n"] / (now - _speed["t"])
            state["speed"] = int(inst if not state["speed"] else state["speed"] * 0.6 + inst * 0.4)
        _speed["t"], _speed["n"] = now, 0
    _speed["n"] += n
    _speed["last"] = now


thumb_cache = {}


async def thumb(m):
    if m.id in thumb_cache:
        return thumb_cache[m.id]
    data = None
    try:
        if m.photo:
            sizes = [s for s in m.photo.sizes if hasattr(s, "w") and not isinstance(s, types.PhotoStrippedSize)]
            pick = sorted([s for s in sizes if s.w >= 300], key=lambda s: s.w)
            ts = pick[0] if pick else (max(sizes, key=lambda s: s.w) if sizes else None)
            if ts is not None:
                data = await client.download_media(m, file=bytes, thumb=ts)
        elif m.document and m.document.thumbs:
            real = [t for t in m.document.thumbs if not isinstance(t, types.PhotoStrippedSize)]
            if real:
                data = await client.download_media(m, file=bytes, thumb=real[-1])
    except Exception as e:
        print("thumb error", m.id, e)
    if len(thumb_cache) > 300:
        thumb_cache.clear()
    if data:
        thumb_cache[m.id] = data
    return data


# ---------------- HTTP (aiohttp) ----------------

async def api_status(request):
    if time.time() - _speed.get("last", 0) > 3:
        state["speed"] = 0
    state["online"] = bool(client and client.is_connected())
    return web.json_response({**state, "count": len(index)})


async def api_list(request):
    items = sorted(index.values(), key=lambda x: x["id"], reverse=True)
    return web.json_response(items)


async def api_scan(request):
    asyncio.ensure_future(scan())
    return web.json_response({})


async def thumb_h(request):
    try:
        m = await get_msg(int(request.match_info["id"]))
    except Exception:
        m = None
    if not m or not m.media:
        raise web.HTTPNotFound()
    data = await thumb(m)
    if not data:
        raise web.HTTPNotFound()
    return web.Response(body=data, content_type="image/jpeg")


async def stream_h(request):
    try:
        m = await get_msg(int(request.match_info["id"]))
    except Exception:
        m = None
    if not m or not m.media:
        raise web.HTTPNotFound()

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
            _count(len(chunk))
            remaining -= len(chunk)
            if remaining <= 0:
                break
    except (ConnectionResetError, asyncio.CancelledError):
        pass  # TV ne seek / stop kiya
    return resp


async def playlist(request):
    lines = ["#EXTM3U"]
    for it in sorted(index.values(), key=lambda x: x["id"], reverse=True):
        if it["kind"] in ("video", "audio"):
            lines += [f"#EXTINF:-1 {it['duration']},{it['title']}",
                      f"{request.scheme}://{request.host}/f/{it['id']}"]
    return web.Response(text="\n".join(lines), content_type="audio/x-mpegurl")


async def home(request):
    import html
    rows = []
    for it in sorted(index.values(), key=lambda x: x["id"], reverse=True):
        t = html.escape(it["title"])
        if it["kind"] == "photo":
            rows.append(f'<a href="/f/{it["id"]}"><img src="/t/{it["id"]}" width="280" loading="lazy"></a>')
        else:
            rows.append(f'<p><a href="/f/{it["id"]}">[{it["kind"]}] {t}</a></p>')
    body = ("<meta name=viewport content='width=device-width'><h2>Telegram TV (remote server)</h2>"
            "<p><a href='/tv.m3u'>VLC playlist</a></p>" + "".join(rows))
    return web.Response(text=body, content_type="text/html")


# ---------------- main ----------------

async def main():
    global client, group, BOOT_TS
    BOOT_TS = time.time()
    client = TelegramClient(str(HERE / "tvbot"), API_ID, API_HASH)
    await client.start(bot_token=BOT_TOKEN)
    real_id, peer_type = utils.resolve_id(GROUP_ID)
    try:
        group = await client.get_input_entity(peer_type(real_id))
    except ValueError:
        # bots group ko access_hash 0 se khol sakte hain
        group = types.InputPeerChannel(real_id, 0)

    load_index()
    try:
        ent = await client.get_entity(group)
        state["title"] = getattr(ent, "title", "") or state["title"]
    except Exception:
        pass

    @client.on(events.NewMessage(chats=GROUP_ID))
    async def on_new(event):
        add(event.message)
        save_index()
        print("Naya:", index[event.message.id]["title"])

    @client.on(events.MessageEdited(chats=GROUP_ID))
    async def on_edit(event):
        msg_cache.pop(event.message.id, None)
        if not add(event.message):
            index.pop(event.message.id, None)
        state["version"] += 1
        save_index()

    @client.on(events.MessageDeleted(chats=GROUP_ID))
    async def on_del(event):
        for mid in event.deleted_ids:
            index.pop(mid, None)
            if state["update"] and state["update"]["id"] == mid:
                state["update"] = None
        state["version"] += 1
        save_index()

    # ---- bot admin commands (sirf admin ke liye, bina login sab kuch bot se chalta hai) ----
    @client.on(events.NewMessage(pattern=r"^/(status|scan|id|help)\b"))
    async def on_cmd(event):
        if event.is_channel and not event.is_group:
            return  # broadcast channel me bot ki reply spam karegi
        if not ADMIN_ID or event.sender_id != ADMIN_ID:
            if event.is_private:
                await event.reply("Ye commands sirf bot admin ke liye hain.")
            return
        cmd = event.pattern_match.group(1)
        if cmd == "id":
            await event.reply(f"chat_id: {event.chat_id}\naapki id: {event.sender_id}")
        elif cmd == "status":
            up = int(time.time() - BOOT_TS) if BOOT_TS else 0
            c = {}
            for v in index.values():
                c[v["kind"]] = c.get(v["kind"], 0) + 1
            big = sum(1 for v in index.values() if v.get("big"))
            lines = [f"{state['title'] or 'Storage'} — remote server status",
                     f"Videos: {c.get('video', 0)} · Gaane: {c.get('audio', 0)} · Photos: {c.get('photo', 0)} · Subs: {c.get('sub', 0)}"]
            if big:
                lines.append(f"⚠ 2GB+ files: {big} (bot inhe nahi chala sakta, Telegram limit)")
            lines.append(f"Connection: {'online' if client and client.is_connected() else 'offline'} · Uptime: {up // 3600}h {(up % 3600) // 60}m")
            upd = state.get("update")
            if upd:
                lines.append(f"Update post mila: v{upd['name']}")
            await event.reply("\n".join(lines))
        elif cmd == "scan":
            await event.reply("Storage dobara padh raha hoon…")
            await scan()
            c = {}
            for v in index.values():
                c[v["kind"]] = c.get(v["kind"], 0) + 1
            await event.reply(f"Ho gaya: {len(index)} files (video {c.get('video', 0)} · gaana {c.get('audio', 0)} · photo {c.get('photo', 0)})" +
                              (f" · channelError: {state['channelError']}" if state["channelError"] else ""))
        elif cmd == "help":
            await event.reply("Commands (sirf admin):\n/status — abhi ka haal\n/scan — storage dobara padho\n/id — chat id batao")

    app = web.Application()
    app.add_routes([
        web.get("/", home),
        web.get("/api/status", api_status),
        web.get("/api/list", api_list),
        web.get("/api/scan", api_scan),
        web.get("/tv.m3u", playlist),
        web.get("/t/{id}", thumb_h),
        web.get("/f/{id}", stream_h),
    ])
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", PORT).start()
    print(f"Ready! TV app me Settings > Server me dalo: http://<is-device-ka-IP>:{PORT}")

    if not index:
        await scan()
    else:
        asyncio.ensure_future(scan())
    state["ready"] = True
    state["error"] = ""
    await client.run_until_disconnected()


if __name__ == "__main__":
    while True:
        try:
            asyncio.run(main())
        except Exception as e:
            state["ready"] = False
            state["error"] = f"{type(e).__name__}: {e}"
            import traceback
            traceback.print_exc()
        time.sleep(5)  # net gaya to dobara try
