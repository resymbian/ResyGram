<h1 align="center">ResyGram</h1>

<p align="center">
  <strong>Telegram for legacy Java phones, rebuilt for everyday use.</strong><br>
  A direct-to-server MTPROTO client for MIDP 2.0 / CLDC 1.1 handsets — no relay, no web wrapper.
</p>

<p align="center">
  <img alt="Java ME: MIDP 2.0 and CLDC 1.1" src="https://img.shields.io/badge/Java_ME-MIDP_2.0_%C2%B7_CLDC_1.1-1769aa">
  <a href="LICENSE"><img alt="License: WTFPL" src="https://img.shields.io/badge/license-WTFPL-1769aa"></a>
</p>

ResyGram is a new client in the [resymbian](https://github.com/resymbian) family,
started from the excellent [J2MEgram](https://github.com/smbdsbrain/J2MEgram) by
smbdsbrain (WTFPL) and developed from there. The protocol stack, the crypto and
the RMS stores come from that codebase; the name, the identity, the packaging and
what this project does next are its own.

First target: the **Nokia E51 (RM-244, S60 3rd)**, because that is the handset this
was built against. Anything with MIDP 2.0 and a socket should have a chance.

## Why another one

The protocol work is done — ResyGram signs in, lists chats, sends, receives,
reacts, shows photos. What it does not do yet is feel like a messenger anyone
would keep open:

- [x] Direct connection without a proxy, including on an unsigned MIDlet (the
      data centres serve MTPROTO above the privileged port range; the client
      falls back to 5222 when the handset refuses 443)
- [x] An icon in the launcher, not only inside the app
- [x] A splash with a spinner while the client builds itself, so a launch looks
      like a launch instead of a black screen
- [x] A setup flow whose primary action is the right soft key: Connect,
      Continue and Sign in are `Command.OK`, the only type Series 60 hands to
      that key, and Exit sits in the left menu. The start screen now says to
      press Connect instead of opening with a diagnostic line.
- [x] Emoji entry: the composer's Emoji command inserts from the same atlas the
      messages are drawn with
- [x] Smaller and faster: a `-Release` JAR is ~30% smaller than the same tree
      built without optimisation and obfuscation (738 KB -> 518 KB)
- [ ] Telegram-style chat bubbles, full screen, composer in the conversation
- [ ] Attachments: thumbnails, download, open with the phone's own viewer
- [ ] Search and join channels, groups and people
- [ ] Upload files and photos from the phone
- [ ] Background delivery with a notification, without draining the battery
- [x] A loading bar, not a dot ring: the splash and every blocking step
      (connect, sign-in, sending) sweep a progress bar so the wait reads as a
      process. The busy screen starts on `showNotify` and stops on `hideNotify`,
      so it cannot keep repainting behind a screen that replaced it.

## Build

The toolchain is PowerShell 7, a JDK 8 and a pinned ProGuard download that
`tools/bootstrap.ps1` fetches:

```sh
./tools/bootstrap.ps1
JDK8_HOME=/path/to/jdk8 ./tools/build.sh -Target tg -Env production -Release -ArtifactName ResyGram-0.1.0
```

The pair lands in `dist/`. `-Release` optimises and obfuscates; leave it off while
debugging, because a stack trace from a handset then names real classes.

### Credentials

`api_id` / `api_hash` come from `my.telegram.org` and are never committed:

- locally: `secrets/telegram.yaml` (gitignored)
- CI: the `TG_API_ID` and `TG_API_HASH` repository secrets, read by
  `tools/_env.ps1` from the environment before any file

Both are generated into `generated/tg/app/Secrets.java` at build time. A publish
build refuses to run with hardcoded development secrets in the tree.

## Install

Copy `ResyGram-<version>.jar` and its matching `.jad` into the same folder on the
phone (USB, Bluetooth, memory card) and open the `.jad`; if the handset refuses
it, open the `.jar` instead. The `.jad` records the exact size of its `.jar`, so
do not rename either file.

In **Settings → Mode**, `Auto` reaches Telegram directly: 443 first, then 5222
when the handset's security policy refuses privileged ports. Leave MTProxy and
HTTP alone unless direct does not work on your network.

## Credits

Protocol and structure: [J2MEgram](https://github.com/smbdsbrain/J2MEgram) by
smbdsbrain, WTFPL. Emoji art: Noto Emoji (Apache 2.0). ResyGram is unofficial and
is not affiliated with or endorsed by Telegram.
