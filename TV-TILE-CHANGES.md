# TV tile — kya badla (JioStar content integration)

Home screen me **Resume** ke bagal me naya pink **TV** tile. Isme JioStar+ app ka
poora live-TV content ab RG TV ke andar hi chalta hai (native, WebView nahi).

## Naye files
- `tv-app/app/src/main/java/com/rgbotz/tv/LiveTvActivity.kt`
  - `Channels`: wahi 3 sources jo JioStar+ app use karta hai
    (lemajekaat.streamxlive.workers.dev, endless.livenoww.workers.dev,
    api.npoint.io/5122554ee81206923fa7). Array / `{channels:[]}` / `{data:[]}` teeno
    format parse hote hain, name/logo/url/channel_name/channel_logo/mpd_url/m3u8_url/
    url_backup/cookie/all_keys — sab wahi fallbacks.
    Dedupe naam se (HLS ko DASH pe tarjeeh), category chips (All/Sports/…/Regional).
    `filesDir/live_channels.json` me cache — agli baar instant open, worker down ho
    to bhi purani list dikhti hai.
  - `LiveLogo`: channel logo loader (LruCache + background threads).
  - `LiveTvActivity`: 5-column grid + category chips + search + retry, sab remote se.
- `tv-app/app/src/main/java/com/rgbotz/tv/LivePlayActivity.kt`
  - ExoPlayer: HLS (.m3u8) + DASH (.mpd) dono.
  - Channel ka `cookie` / pipe-style `url|Cookie=…` headers me jata hai.
  - `all_keys` / `kid:key` → **ClearKey DRM** inline (data: license URI);
    `license_url` → Widevine.
  - Error par **backup URL auto-try**, phir error panel (Dobara / Channel list).
  - Remote: Up/Down = channel zap, OK = play/pause, Menu = channel list, Back = wapas.
- `res/layout/activity_live_tv.xml`, `activity_live_play.xml`, `item_channel.xml`
- `res/drawable/tile_tv.xml` (JioStar pink gradient), `ic_tv.xml`

## Badle files
- `res/layout/activity_main.xml` — Resume ke baad `tileTV` (5 tiles, sab weight=1)
- `java/.../MainActivity.kt` — tile click → LiveTvActivity, `nTV` par cached count
- `java/.../Prefs.kt` — `tvCount`, `tvCategory` (last category yaad rehti hai)
- `AndroidManifest.xml` — LiveTvActivity + LivePlayActivity registered
- `app/build.gradle` — media3 `exoplayer-hls` + `exoplayer-dash` 1.8.0 added
- `README.md` — TV tile line

Build wahi purana GitHub Actions workflow (`Build TV APK`) se hogi — koi aur
change nahi chahiye.

## Server switch (v2.3)

TV tile me sabse upar **Server:** row aa gayi — teen servers, remote se switch karo:
- **JioStar** (default): pehle wale 3 sources (2 workers + npoint 5122554…)
- **+PlusTV**: +PlusTV app ka npoint JSON (~940 channels, `channel_url`/`channel_name`/`channel_logo`/`channel_genre` + ClearKey `key`/`keyId`)
- **InPlex+**: InPlex+ app ka npoint JSON (~937 channels, same format)

- Switch karne par dusre server ka cache turant dikhta hai, fresh list background me fetch hoti hai.
- Har server ka apna offline cache (`live_channels_0/1/2.json`); purani single-file cache JioStar ke liye fallback hai.
- Selected server + category yaad rehti hai (Prefs: `tvServer`).
- Parse me naye fields bhi: `channel_logo` (logo), `channel_genre` (category chips).
