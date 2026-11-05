# Telegram TV

Telegram channel ki video, music, photo seedha Mi TV 4A par. Server app ke andar hi chalta hai (Python + Telethon via Chaquopy), TV storage use nahi hota.

- Build: Actions > Build TV APK > Run workflow (config input base64 JSON {api_hash, bot_token}).
- Remote: OK = play, Back = wapas, Menu = refresh, player me CC = captions, settings gear = audio language, Left/Right = 15 sec.
- **TV tile (Live TV)**: Home me Resume ke bagal me naya TV tile — JioStar jaise live channels (HLS + DASH, cookie/ClearKey DRM support). Channel list 3 sources se aati hai, cache hoti hai (offline bhi dikhegi). Player me Up/Down = channel badlo, OK = play/pause, Menu/Back = channel list, error par backup URL auto-try.
- Har audio (AC3, EAC3, DTS, TrueHD, FLAC, Opus) FFmpeg se chalta hai.
- server/ folder: optional PC version same server ka.
- Limit: Telegram file 2GB (free) / 4GB (Premium).
