# TV ke andar chalne wala chhota server: Telegram channel -> http://127.0.0.1:PORT
# Kuch bhi TV storage me save nahi hota (sirf chhoti index list aur bot session).

import asyncio, ctypes, json, os, random, re, sys, threading, types as pytypes, traceback

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

from telethon import TelegramClient, events, functions, utils, types  # noqa: E402
from telethon.sessions import StringSession  # noqa: E402
from telethon.errors import FloodWaitError  # noqa: E402

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


async def _purge_dc_sender(doc):
    """Doosre DC ka cached (mar chuka) sender hatao — agla attempt fresh auth banayega.
    Telethon dead exported sender ko cache me hi rakhta hai, jisse door DC ki file
    kabhi-kabhi hamesha ke liye atak jaati hai (video load nahi hoti)."""
    try:
        dc = getattr(doc, "dc_id", 0) or 0
        if dc and client and dc != client.session.dc_id:
            ent = client._borrowed_senders.pop(dc, None)
            if ent:
                try:
                    await ent[1].disconnect()
                except Exception:
                    pass
    except Exception:
        pass


async def _get_chunk(doc, off, size, mid=0):
    """Ek CHUNK download karo, retry + reconnect ke saath.
    Pehle ek bhi chunk fail hota to poora stream hamesha ke liye mar jata tha
    (video 'load nahi hoti'). Ab har chunk 5 baar try hota hai: FloodWait ka wait,
    transient error par reconnect + backoff — isse dheelare DC din bhi chalega.
    Doosre DC ka sender 2 fail par purge ho jata hai (fresh auth)."""
    last = None
    for attempt in range(5):
        try:
            async for c in client.iter_download(doc, offset=off, request_size=CHUNK,
                                                limit=1, file_size=size):
                return bytes(c)
            return b""
        except FloodWaitError as e:
            last = e
            await asyncio.sleep(min(float(e.seconds + 1), 60.0))
        except Exception as e:
            last = e
            await asyncio.sleep(min(2.0 ** attempt, 10.0))
            try:
                if not client.is_connected():
                    await client.connect()
            except Exception:
                pass
            if attempt >= 1:
                await _purge_dc_sender(doc)
    if mid:
        note_stream_fail(mid, doc)
    raise RuntimeError(f"chunk@{off} 5 baar fail: {last}")


async def parallel_chunks(doc, offset, size, mid=0):
    total = -(-(size - offset) // CHUNK)
    dc = getattr(doc, "dc_id", 0) or 0
    far = bool(dc and client and dc != client.session.dc_id)
    # door DC me latency zyada hoti hai — extra parallel streams se speed aati hai
    n = max(1, min(6 if far else PARALLEL, total))
    start_idx = offset // CHUNK
    queues = [asyncio.Queue(maxsize=3) for _ in range(n)]

    async def worker(i):
        try:
            idx = start_idx + i
            end_idx = start_idx + total
            while idx < end_idx:
                c = await _get_chunk(doc, idx * CHUNK, size, mid)
                await queues[i].put(c)
                idx += n
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


# ---------------- door-DC file self-heal (auto re-upload) ----------------
# Koi file doosre (door) Telegram DC pe padi ho aur uska stream 2 baar mar jaye,
# to bot KHUD uska data stream karke naye parts upload karta hai (bina disk ke —
# 1MB chunk download -> 2x512KB part upload, ek saath). Nayi copy ghar wale DC pe
# ban jaati hai, verify hoti hai, purani delete. TV pe wo file hamesha ke liye tez.

UP_PART = 512 * 1024
HEAL_WORKERS = 3
heal_state = {}          # mid(int) -> {status,pct,name,new_id,err}
_heal_queue = []
_heal_running = False
_fail_count = {}
_heal_tried = set()


def heal_path():
    return _p("heal.json")


def heal_load():
    try:
        with open(heal_path()) as f:
            return json.load(f)
    except Exception:
        return {}


def heal_save(d):
    try:
        tmp = str(heal_path()) + ".tmp"
        with open(tmp, "w") as f:
            json.dump(d, f)
        os.replace(tmp, heal_path())
    except Exception:
        pass


def queue_heal(mid, doc, force=False):
    if not client or not doc:
        return
    if not force and mid in _heal_tried:
        return
    _heal_tried.add(mid)
    size = getattr(doc, "size", 0) or 0
    if size <= 0 or size > BOT_DL_LIMIT:
        return  # 2GB+ file bot upload hi nahi kar sakta
    dc = getattr(doc, "dc_id", 0) or 0
    if not dc or dc == client.session.dc_id:
        return  # ghar wale DC pe hai, heal ki zaroorat nahi
    hs = heal_state.get(mid)
    if hs and hs.get("status") in ("queued", "running"):
        return
    heal_state[mid] = {"status": "queued", "pct": 0, "name": "", "new_id": 0, "err": ""}
    _heal_queue.append(mid)
    asyncio.ensure_future(_heal_pump())


def note_stream_fail(mid, doc):
    """Stream mara to gino — 2 baar mar jaye to self-heal shuru."""
    try:
        _fail_count[mid] = _fail_count.get(mid, 0) + 1
        if _fail_count[mid] >= 2:
            queue_heal(mid, doc)
    except Exception:
        pass


def resume_heals():
    """Boot par: pichle adhoore heal wapas shuru karo (app band ho gaya tha)."""
    try:
        for mid, rs in heal_load().items():
            mid = int(mid)
            _heal_tried.add(mid)
            heal_state[mid] = {"status": "queued", "pct": 0, "name": rs.get("name", ""),
                               "new_id": 0, "err": ""}
            _heal_queue.append(mid)
        if _heal_queue:
            asyncio.ensure_future(_heal_pump())
    except Exception:
        pass


async def _heal_pump():
    global _heal_running
    if _heal_running:
        return
    _heal_running = True
    try:
        while _heal_queue:
            mid = _heal_queue.pop(0)
            try:
                await _heal_run(mid)
            except Exception as e:
                hs = heal_state.get(mid) or {"pct": 0, "name": "", "new_id": 0}
                hs.update(status="failed", err=f"{type(e).__name__}: {str(e)[:80]}")
                heal_state[mid] = hs
    finally:
        _heal_running = False


async def _heal_run(mid):
    m = await client.get_messages(group, ids=mid)
    if not m or not m.document:
        heal_state.pop(mid, None)
        return
    doc = m.document
    size = doc.size
    heal_state[mid] = {"status": "running", "pct": 0, "name": (m.file.name or "")[:60],
                       "new_id": 0, "err": ""}
    rs = heal_load().get(str(mid), {})
    fid = rs.get("file_id") or random.randrange(1, 2 ** 63)
    parts_total = -(-size // UP_PART)
    ul_done = set(rs.get("ul_done", []))
    if rs.get("size") != size:
        ul_done = set()
    CH = 2 * UP_PART  # 1MB download -> 2 part upload
    nchunks = -(-size // CH)
    todo_c = [c for c in range(nchunks)
              if any(p not in ul_done for p in range(2 * c, min(2 * c + 2, parts_total)))]
    lock = asyncio.Lock()
    stop = asyncio.Event()

    async def work(wid):
        while True:
            async with lock:
                if stop.is_set() or not todo_c:
                    return
                cidx = todo_c.pop(0)
            off = cidx * CH
            data = None
            for att in range(6):
                try:
                    buf = bytearray()
                    async for c in client.iter_download(doc, offset=off, request_size=CH,
                                                        limit=1, file_size=size):
                        buf += c
                    data = bytes(buf)
                    break
                except FloodWaitError as e:
                    await asyncio.sleep(min(e.seconds + 1, 120))
                except Exception:
                    if att >= 1:
                        await _purge_dc_sender(doc)
                    await asyncio.sleep(min(2.0 ** att, 10.0))
            if not data:
                stop.set()
                heal_state[mid]["err"] = f"download chunk {cidx} fail"
                return
            for p in range(2 * cidx, min(2 * cidx + 2, parts_total)):
                if p in ul_done:
                    continue
                seg = data[p * UP_PART - off: p * UP_PART - off + UP_PART]
                for att in range(4):
                    try:
                        await client(functions.upload.SaveBigFilePartRequest(fid, p, parts_total, seg))
                        break
                    except FloodWaitError as e:
                        await asyncio.sleep(min(e.seconds + 1, 120))
                    except Exception:
                        await asyncio.sleep(min(2.0 ** att, 8.0))
                else:
                    stop.set()
                    heal_state[mid]["err"] = f"upload part {p} fail"
                    return
                async with lock:
                    ul_done.add(p)
                    heal_state[mid]["pct"] = int(len(ul_done) * 100 / parts_total)
                    if len(ul_done) % 20 == 0 or len(ul_done) == parts_total:
                        heal_save({str(mid): {"file_id": fid, "size": size,
                                              "ul_done": sorted(ul_done)}})

    await asyncio.gather(*[work(i) for i in range(HEAL_WORKERS)])
    if stop.is_set():
        heal_state[mid]["status"] = "failed"
        return
    # sab parts upload ho gaye — ab asli caption/attributes ke saath message bhejo
    m = await client.get_messages(group, ids=mid)
    if not m or not m.document:
        heal_state[mid]["status"] = "failed"
        heal_state[mid]["err"] = "source message gayab"
        return
    d2 = m.document
    fname = next((a.file_name for a in d2.attributes
                  if isinstance(a, types.DocumentAttributeFilename)), "video.mkv")
    attrs = list(d2.attributes)  # filename attr bhi saath jaye (InputFileBig ka name kaafi nahi hota)
    r = await client(functions.messages.SendMediaRequest(
        group, types.InputMediaUploadedDocument(
            file=types.InputFileBig(fid, parts_total, fname),
            mime_type=d2.mime_type or "video/mp4",
            attributes=attrs, force_file=True),
        message=m.message or "", random_id=random.randrange(1, 2 ** 63)))
    new_id = 0
    for u in (getattr(r, "updates", None) or []):
        if isinstance(u, (types.UpdateNewChannelMessage, types.UpdateNewMessage)):
            new_id = u.message.id
    if not new_id:
        raise RuntimeError("nayi message id nahi mili")
    ok = False
    m2 = await client.get_messages(group, ids=new_id)
    if m2 and m2.document:
        try:
            async for _c in client.iter_download(m2.document, offset=0, request_size=UP_PART,
                                                 limit=1, file_size=m2.document.size):
                pass
            ok = True
        except Exception:
            ok = False
    if ok:
        try:
            await client.delete_messages(group, [mid])
        except Exception:
            pass
        heal_state[mid] = {"status": "done", "pct": 100, "name": heal_state[mid].get("name", ""),
                           "new_id": new_id, "err": ""}
        d = heal_load()
        d.pop(str(mid), None)
        heal_save(d)
    else:
        heal_state[mid]["status"] = "failed"
        heal_state[mid]["err"] = "nayi copy verify fail"


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
            state["heal"] = {str(k): v for k, v in heal_state.items()}
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
        async for chunk in parallel_chunks(m.document, aligned, size, mid=m.id):
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
    resume_heals()
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
            hh = [v for v in heal_state.values() if v.get("status") in ("queued", "running")]
            if hh:
                lines.append(f"🛠 Self-heal: {hh[0].get('name') or 'file'} — {hh[0].get('pct', 0)}%")
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
