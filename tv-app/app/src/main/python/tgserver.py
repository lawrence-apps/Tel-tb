# TV ke andar chalne wala chhota server: Telegram channel -> http://127.0.0.1:PORT
# Kuch bhi TV storage me save nahi hota (sirf chhoti index list aur bot session).

import asyncio, ctypes, json, os, re, sys, threading, types as pytypes, traceback

# ---------- Fast AES (OpenSSL) for Telethon, warna pure-python bahut slow hai ----------

def _install_fast_aes():
    try:
        import ssl  # noqa: libcrypto load karwata hai
        found = []
        with open("/proc/self/maps") as f:
            for line in f:
                p = line.split()[-1] if line.split() else ""
                if "libcrypto" in p and ".so" in p and p not in found:
                    found.append(p)
        # Chaquopy ka asli OpenSSL libcrypto_python.so hai
        found.sort(key=lambda p: 0 if "libcrypto_python" in p else 1)
        found += ["libcrypto_python.so", "libcrypto.so"]
        lib, path = None, None
        for p in found:
            try:
                l = ctypes.CDLL(p)
                l.AES_ige_encrypt  # symbol check
                lib, path = l, p
                break
            except Exception:
                continue
        if lib is None:
            print("tgserver: libcrypto nahi mila, slow AES")
            return
        lib.AES_set_encrypt_key.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_void_p]
        lib.AES_set_decrypt_key.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_void_p]
        lib.AES_ige_encrypt.argtypes = [ctypes.c_char_p, ctypes.c_void_p, ctypes.c_size_t,
                                        ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int]

        def _ige(data, key, iv, enc):
            data = bytes(data)
            k = ctypes.create_string_buffer(260)
            if enc:
                lib.AES_set_encrypt_key(bytes(key), len(key) * 8, k)
            else:
                lib.AES_set_decrypt_key(bytes(key), len(key) * 8, k)
            out = ctypes.create_string_buffer(len(data))
            ivb = ctypes.create_string_buffer(bytes(iv), len(iv))
            lib.AES_ige_encrypt(data, out, len(data), k, ivb, 1 if enc else 0)
            return out.raw

        mod = pytypes.ModuleType("cryptg")
        mod.encrypt_ige = lambda p, k, i: _ige(p, k, i, True)
        mod.decrypt_ige = lambda c, k, i: _ige(c, k, i, False)
        sys.modules["cryptg"] = mod
        print("tgserver: fast AES ready via", path)
    except Exception as e:
        print("tgserver: fast AES fail", e)


_install_fast_aes()

from telethon import TelegramClient, events, utils, types  # noqa: E402
from telethon.sessions import StringSession  # noqa: E402

CHUNK = 512 * 1024
PARALLEL = 4
VIDEO_EXT = (".mkv", ".mp4", ".avi", ".webm", ".ts", ".mov", ".m4v", ".flv")
AUDIO_EXT = (".mp3", ".m4a", ".flac", ".ogg", ".opus", ".wav", ".aac")
# Telegram bots 2GB se badi file download nahi kar sakte (premium user session chahiye).
# Isse badi files index me "big" mark hoti hain: app unhe dikhata hai par clear message deta hai.
BOT_DL_LIMIT = 2000 * 1024 * 1024

state = {"ready": False, "error": "", "scanning": False, "scanned": 0, "version": 0,
         "title": "", "update": None, "speed": 0, "online": True, "channelError": ""}
BOOT_TS = 0.0
_speed = {"t": 0.0, "n": 0}
index = {}
client = None
group = None
loop = None
cfg = {}


def _p(name):
    return os.path.join(cfg["dir"], name)


def save_index():
    tmp = _p("index.json.tmp")
    with open(tmp, "w") as f:
        json.dump({"_v": 2, "items": index, "update": state["update"], "title": state["title"]}, f, ensure_ascii=False)
    os.replace(tmp, _p("index.json"))


def load_index():
    try:
        with open(_p("index.json")) as f:
            data = json.load(f)
        if data.get("_v") == 2:
            index.update({int(k): v for k, v in data["items"].items()})
            state["update"] = data.get("update")
            state["title"] = data.get("title", "")
            state["version"] += 1
    except Exception:
        pass


SUB_EXT = (".srt", ".vtt", ".ass", ".ssa")
PHOTO_EXT = (".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif")
UPD = re.compile(r"^(?:TGTV|RGTV)-UPDATE\s+(\d+)\s*(\S*)")


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
                import base64
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


async def periodic_rescan():
    # Har 15 min me ek light dobara-scan: live event miss ho jaye (net gaya tha) ya kisi ne
    # channel se kuch add/remove kiya ho, to ye bhi pakad lega. App ka poll version badalne
    # par list khud refresh kar leta hai.
    while True:
        await asyncio.sleep(900)
        try:
            if not state["scanning"]:
                await scan()
        except Exception:
            pass


async def scan():
    if state["scanning"]:
        return
    state["scanning"] = True
    try:
        start, empty = 1, 0
        found = {}
        while empty < 5:
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
    except Exception as e:
        state["channelError"] = f"{type(e).__name__}: {e}"
    finally:
        state["scanning"] = False


msg_cache = {}


async def get_msg(mid):
    import time
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
            print("tgserver download error:", e)
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
    import time
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


# ---------------- minimal HTTP server ----------------

def _head(status, headers):
    reason = {200: "OK", 206: "Partial Content", 404: "Not Found",
              416: "Range Not Satisfiable", 500: "Error"}.get(status, "OK")
    lines = [f"HTTP/1.1 {status} {reason}"] + [f"{k}: {v}" for k, v in headers.items()]
    lines += ["Connection: close", "", ""]
    return "\r\n".join(lines).encode()


async def _send(w, status, body, ctype):
    w.write(_head(status, {"Content-Type": ctype, "Content-Length": len(body)}) + body)
    await w.drain()


async def handle(reader, writer):
    try:
        req = await reader.readline()
        if not req:
            return
        method, path, _ = req.decode("latin1").split(" ", 2)
        headers = {}
        while True:
            line = await reader.readline()
            if line in (b"\r\n", b"\n", b""):
                break
            k, _, v = line.decode("latin1").partition(":")
            headers[k.strip().lower()] = v.strip()

        if path == "/api/status":
            import time
            if time.time() - _speed.get("last", 0) > 3:
                state["speed"] = 0
            state["online"] = bool(client and client.is_connected())
            body = json.dumps({**state, "count": len(index)}).encode()
            return await _send(writer, 200, body, "application/json")
        if path == "/api/list":
            items = sorted(index.values(), key=lambda x: x["id"], reverse=True)
            return await _send(writer, 200, json.dumps(items, ensure_ascii=False).encode(), "application/json")
        if path == "/api/scan":
            asyncio.ensure_future(scan())
            return await _send(writer, 200, b"{}", "application/json")

        mm = re.match(r"^/([ft])/(\d+)", path)
        if not mm:
            return await _send(writer, 404, b"not found", "text/plain")
        m = await get_msg(int(mm.group(2)))
        if not m or not m.media:
            return await _send(writer, 404, b"not found", "text/plain")
        if mm.group(1) == "t":
            data = await thumb(m)
            if not data:
                return await _send(writer, 404, b"no thumb", "text/plain")
            return await _send(writer, 200, data, "image/jpeg")

        if m.photo:
            data = await client.download_media(m, file=bytes)
            return await _send(writer, 200, data, "image/jpeg")

        size = m.file.size
        start, end = 0, size - 1
        rng = headers.get("range")
        if rng:
            r = re.match(r"bytes=(\d*)-(\d*)", rng)
            if r and r.group(1):
                start = int(r.group(1))
                if r.group(2):
                    end = min(int(r.group(2)), size - 1)
            elif r and r.group(2):
                start = max(size - int(r.group(2)), 0)
        if start >= size:
            writer.write(_head(416, {"Content-Range": f"bytes */{size}", "Content-Length": 0}))
            return await writer.drain()
        length = end - start + 1
        h = {"Content-Type": m.file.mime_type or "application/octet-stream",
             "Accept-Ranges": "bytes", "Content-Length": length}
        if rng:
            h["Content-Range"] = f"bytes {start}-{end}/{size}"
        writer.write(_head(206 if rng else 200, h))
        await writer.drain()
        if method == "HEAD":
            return

        aligned = start - (start % CHUNK)
        skip = start - aligned
        remaining = length
        async for chunk in parallel_chunks(m.document, aligned, size):
            if skip:
                chunk, skip = chunk[skip:], 0
            chunk = chunk[:remaining]
            writer.write(chunk)
            await writer.drain()
            remaining -= len(chunk)
            _count(len(chunk))
            if remaining <= 0:
                break
    except (ConnectionError, asyncio.CancelledError, BrokenPipeError):
        pass
    except Exception:
        traceback.print_exc()
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def _main():
    global client, group, BOOT_TS
    import time
    BOOT_TS = time.time()
    sess_file = _p("bot.session.txt")
    sess = open(sess_file).read().strip() if os.path.exists(sess_file) else ""
    client = TelegramClient(StringSession(sess), cfg["api_id"], cfg["api_hash"])
    await client.start(bot_token=cfg["bot_token"])
    with open(sess_file, "w") as f:
        f.write(client.session.save())

    real_id, peer_type = utils.resolve_id(cfg["group_id"])
    try:
        group = await client.get_input_entity(peer_type(real_id))
    except ValueError:
        group = types.InputPeerChannel(real_id, 0)

    load_index()
    try:
        ent = await client.get_entity(group)
        state["title"] = getattr(ent, "title", "") or state["title"]
    except Exception:
        pass

    @client.on(events.NewMessage(chats=cfg["group_id"]))
    async def on_new(event):
        add(event.message)
        save_index()

    @client.on(events.MessageEdited(chats=cfg["group_id"]))
    async def on_edit(event):
        msg_cache.pop(event.message.id, None)
        if not add(event.message):
            index.pop(event.message.id, None)
        state["version"] += 1
        save_index()

    @client.on(events.MessageDeleted(chats=cfg["group_id"]))
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
            return  # broadcast channel me bot ki reply spam karegi, wahan ignore karo
        if not cfg.get("admin_id") or event.sender_id != cfg["admin_id"]:
            if event.is_private:
                await event.reply("Ye commands sirf bot admin ke liye hain.")
            return
        cmd = event.pattern_match.group(1)
        if cmd == "id":
            await event.reply(f"chat_id: {event.chat_id}\naapki id: {event.sender_id}")
        elif cmd == "status":
            import time
            up = int(time.time() - BOOT_TS) if BOOT_TS else 0
            c = {}
            for v in index.values():
                c[v["kind"]] = c.get(v["kind"], 0) + 1
            big = sum(1 for v in index.values() if v.get("big"))
            lines = [f"{state['title'] or 'Storage'} — status",
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

    srv = await asyncio.start_server(handle, "127.0.0.1", cfg["port"])
    if not index:
        await scan()
    else:
        asyncio.ensure_future(scan())
    asyncio.ensure_future(periodic_rescan())
    state["ready"] = True
    state["error"] = ""
    async with srv:
        await client.run_until_disconnected()


def _run():
    global loop
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    while True:
        try:
            loop.run_until_complete(_main())
        except Exception as e:
            state["ready"] = False
            state["error"] = f"{type(e).__name__}: {e}"
            traceback.print_exc()
        import time
        time.sleep(5)  # net gaya to dobara try


def start(files_dir, api_id, api_hash, bot_token, group_id, port, admin_id=0):
    """Kotlin se call hota hai. Background thread me server chalata hai."""
    if cfg:
        return
    cfg.update(dir=str(files_dir), api_id=int(api_id), api_hash=str(api_hash),
               bot_token=str(bot_token), group_id=int(group_id), port=int(port),
               admin_id=int(admin_id or 0))
    threading.Thread(target=_run, daemon=True).start()
