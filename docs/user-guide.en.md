# NetPopUp — user guide (v0)

NetPopUp sends messages **phone to phone**, with no account, no server and no Internet. Every phone also relays
other people's messages, so a message may cross several phones before it arrives.

## How it works
- Nearby phones find each other over Bluetooth / Wi-Fi (Nearby). Open the app once; it then keeps running in the
  background behind a **permanent notification** (which never shows message content).
- Delivery is **not instant**: a message leaves when a phone able to carry it comes within range. It can take
  minutes — or hours.
- Messages are **ephemeral**: 6 h by default, 24 h at most, then they disappear from every phone.

## Three kinds of conversation
1. **Contact (1:1)** — end-to-end encrypted. Exchange **contact links** in person (copy/paste or QR). Compare the
   safety number with your contact to be sure who they are. Message states: *queued* (on your phone), *relayed*
   (handed to at least one phone), *acknowledged* (the recipient got it).
2. **Private room** — encrypted, for a group (32 members max). Join with an **invitation link** handed over in
   person. The link grants **no access**: a member must accept the request. At creation you choose who may accept:
   *any member* or *the creator only*. Only the creator removes members.
3. **Public local room** — open to anyone in range. **Everyone can read it**; never use it for private matters.
   It is always labelled "public".

## Permissions
| Android | Why |
|---|---|
| 10-11 | Location (required by Bluetooth scanning; your location is neither stored nor sent) |
| 12 | Bluetooth (scan, be visible, connect) + location |
| 13+ | Bluetooth + nearby Wi-Fi devices + notifications |
If you refuse, the app stays readable but exchanges nothing. You can re-enable them in Android settings.

## Good to know
- **Short range**: a few metres to a few tens of metres; less through walls.
- **No recovery**: lose or reinstall the phone and your contacts and rooms are gone. Android backup is
  deliberately disabled.
- **Screenshots are blocked** inside the app.
- **Removed member**: may still read a few messages already in flight until they expire.
- **Presence is observable**: someone listening to the radio can tell phones are nearby, even without reading messages.

## Licence and source code
NetPopUp is free software under the **GNU AGPL-3.0**. Source: https://github.com/cortexAI-2025/netpopup
