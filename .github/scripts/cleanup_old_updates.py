#!/usr/bin/env python3
"""
Naya update post hote hi PURANE updates khud delete:
  1) Storage channel me purane TGTV-UPDATE/RGTV-UPDATE posts (naya chhod ke)
  2) Bot admin DM me purani APK backup copies (nayi chhod ke)
Env: API_ID, API_HASH, BOT_TOKEN, CHANNEL_ID, ADMIN_ID, NEW_CH_MSG, NEW_DM_MSG
"""
import asyncio, os, re
from telethon import TelegramClient
from telethon.sessions import StringSession

UPD = re.compile(r"^(?:TGTV|RGTV)-UPDATE\s+\d+")
DM_MARKS = ("RG-TV v", "RC-TV v", "backup copy", "forward karo")


async def main():
    api_id = int(os.environ["API_ID"])
    api_hash = os.environ["API_HASH"]
    bot_token = os.environ["BOT_TOKEN"]
    channel = int(os.environ["CHANNEL_ID"])
    admin = int(os.environ["ADMIN_ID"])
    keep_ch = int(os.environ.get("NEW_CH_MSG", "0") or 0)
    keep_dm = int(os.environ.get("NEW_DM_MSG", "0") or 0)

    client = TelegramClient(StringSession(), api_id, api_hash)
    await client.start(bot_token=bot_token)

    # 1) channel ke purane update posts
    if keep_ch:
        old = []
        async for m in client.iter_messages(channel, limit=300):
            if m.id == keep_ch:
                continue
            if m.document and m.message and UPD.match(m.message.strip()):
                old.append(m.id)
        if old:
            try:
                await client.delete_messages(channel, old)
            except Exception:
                for mid in old:
                    try:
                        await client.delete_messages(channel, [mid])
                    except Exception as e:
                        print(f"channel msg {mid} delete fail: {e}")
            print(f"channel: {len(old)} purane update post hate: {old}")
        else:
            print("channel: koi purana update post nahi mila")
    else:
        print("channel: NEW_CH_MSG nahi mila — skip (naya post safe)")

    # 2) admin DM ki purani APK copies
    if keep_dm:
        old_dm = []
        async for m in client.iter_messages(admin, limit=100, from_user="me"):
            if m.id == keep_dm:
                continue
            if m.document and m.message and any(k in m.message for k in DM_MARKS):
                old_dm.append(m.id)
        if old_dm:
            try:
                await client.delete_messages(admin, old_dm)
            except Exception:
                for mid in old_dm:
                    try:
                        await client.delete_messages(admin, [mid])
                    except Exception as e:
                        print(f"DM msg {mid} delete fail: {e}")
            print(f"admin DM: {len(old_dm)} purani copy hate: {old_dm}")
        else:
            print("admin DM: koi purani copy nahi mili")
    else:
        print("admin DM: NEW_DM_MSG nahi mila — skip (nayi copy safe)")

    await client.disconnect()


asyncio.run(main())
