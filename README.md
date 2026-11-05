# Telegram TV

Telegram channel ki video, music, photo seedha Mi TV 4A par. Server app ke andar hi chalta hai (Python + Telethon via Chaquopy), TV storage use nahi hota.

- **Login nahi chahiye** — sab kuch bot token se chalta hai; customer ko sirf bot token, admin id aur storage group dena hota hai.
- Build: Actions > **Build TV APK** > Run workflow. Config repo secrets me rehti hai (TG_API_HASH, TG_BOT_TOKEN, TV_KS_B64, TV_KS_PASS) — input khali chhodo, seedha build karo. Purana tareeka (base64 JSON {api_hash, bot_token} input) bhi chalta hai.
- Remote: OK = play, Back = wapas, Menu = refresh, player me CC = captions, settings gear = audio language, Left/Right = 15 sec.
- **TV tile (Live TV)**: Home me Resume ke bagal me TV tile — live channels (HLS + DASH, cookie/ClearKey DRM support). Upar **Server switch**: JioStar / +PlusTV / InPlex+ (dono naye servers ~940 channels). Per-server cache (offline bhi dikhegi). Player me Up/Down = channel badlo, OK = play/pause, Menu/Back = channel list, error par backup URL auto-try.
- Har audio (AC3, EAC3, DTS, TrueHD, FLAC, Opus) FFmpeg se chalta hai — low-end TV ka decoder fail ho to software decode.
- **Bot admin commands** (sirf admin id se): `/status`, `/scan`, `/id`, `/help`.
- **2GB+ files**: Telegram bots 2GB tak hi files nikaal sakte hain (4GB ke liye premium user login chahiye, jo app me jaan-boojh kar nahi hai — no login). 2GB se badi video list me dikhegi, par player seedha saaf message dega.
- server/ folder: optional PC version same server ka (.env.example copy karke .env banao).
- Limit: Telegram file 2GB (bot) / 4GB (premium user session — is app me nahi).
