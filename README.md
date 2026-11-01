<p align="center"><img src="docs/banner.png" width="260" alt="AirTaskPlay"></p>

<p align="center"><b>An offline music player for Android with AirPlay, a built-in web remote and Tasker control.</b></p>

<p align="center">Made with help of ♥ Claude by Ruuh</p>

---

## What it does
AirTaskPlay plays the audio already on your phone, works fully offline, and can be controlled from any browser on your Wi-Fi, from Tasker, or from an iPhone through AirPlay.

- **Local player** with notification and lock-screen controls
- **Web player** on `http://PHONE_IP:1826` for any phone, tablet or computer on the same Wi-Fi
- **AirPlay speaker** (audio only): play from an iPhone or Mac straight to the phone
- **Tasker** control over HTTP or intents, with status broadcasts back
- **Library**: audios, artists, playlists, a Liked list, sorting, search, cover art, and a choice between all music or selected folders
- **History** for audiobook-style listening: *Resume Playing* keeps the timestamp of unfinished files, *Play Again* lists finished ones
- **Profiles with 4-digit PINs**, so each person has their own history
- Starts by itself when the phone boots

## Install
1. Open **Releases** and download `AirTaskPlay.apk`.
2. Install it (allow "install unknown apps" if Android asks) and open it.
3. Allow the audio and notification permissions.

On some phones you also need to allow auto-start and turn off battery optimization for AirTaskPlay so it can start at boot and stay reachable.

## The app
Three full-screen pages, switched from the bar at the top:
- **Player**: the web player, full screen
- **Profiles**: reset a forgotten PIN or delete a profile. This can only be done on the phone
- **AirPlay**: switch, name, live status and restart

Tap the address at the top to copy it. The **i** button shows credits.

## AirPlay
AirPlay is on by default and starts with the phone. It is audio only, with no mirroring or video.

- A device that connects or starts streaming **pauses** the music playing on the phone
- Pressing play on the phone **disconnects** the AirPlay device
- Only one device can be connected at a time
- Rename it, switch it off, or let it stay reachable with the screen off (uses more battery) on the AirPlay page

## Profiles and PINs
Each profile has a 4-digit PIN checked on the phone. Five wrong tries lock that profile for five minutes. A PIN can only be reset in the Android app. The web player uses plain HTTP, so treat the PIN as a privacy lock for people on your Wi-Fi, not strong security.

## Tasker
- **HTTP Request**: `GET http://127.0.0.1:1826/api/toggle`
- **Intent**: Send Intent, action `app.ruuh.airtaskplay.CMD`, target Broadcast Receiver, extra `cmd:toggle` (other extras: `index`, `ms`, `level`, `on`, `mode`)
- **Status**: Event > Intent Received, action `app.ruuh.airtaskplay.STATUS` (extras `playing`, `title`, `artist`, `index`)
- **Start the service**: Send Intent, target Service, package `app.ruuh.airtaskplay`, class `app.ruuh.airtaskplay.PlayerService`

The port is 1826, and falls back to 1831 to 1833 if busy.

## HTTP API
All endpoints are under `/api/` and return JSON.

| Endpoint | Action |
|---|---|
| `status` | Current state |
| `play` `playctx?src=&sort=&id=` `resume?id=` | Play, play a track inside a list, resume from the saved time |
| `pause` `toggle` `stop` `next` `prev` | Transport |
| `seek?ms=` `volume?level=0-100` | Position and volume |
| `shuffle?on=1` `repeat?mode=off\|one\|all` `autoplay?on=1` | Modes |
| `like` `like?id=` | Like the current or a given audio |
| `list?src=all\|artist:NAME\|pl:ID&sort=title\|artist\|new\|old\|manual` | Audios in a view |
| `artists` `playlists` `settings` | Browse data |
| `plcreate?name=` `pladd?pl=&id=` `plremove` `plmove?dir=-1\|1` `pldelete?pl=` | Playlists |
| `setmode?mode=all\|folders` `folder?path=&on=1` `rescan` | Library source |
| `history` `histdel?id=` `histclear?cat=resume\|again\|all` | Listening history (needs a signed-in profile) |
| `profiles` `signup?name=&pin=` `login?u=&pin=` `profile?name=` | Profiles |
| `airplay?on=1` `airname?name=` `airrestart` | AirPlay |
| `art?id=&s=300` | Cover art |

## Build it yourself
No Android Studio needed. Push this repo to GitHub and the **Build APK** workflow produces `AirTaskPlay.apk` (Actions tab, or attached to a release when you push a tag such as `v1.0`; a tag containing a dash, like `v1.0-beta`, is marked as a pre-release). The workflow fetches the AirPlay native code at a pinned commit and builds it with the Android NDK, so the first build takes a while.

## Credits and license
AirPlay support comes from [android-airplay-server](https://github.com/jqssun/android-airplay-server) by jqssun, built on [UxPlay](https://github.com/FDH2/UxPlay). Because of that, AirTaskPlay is licensed under **GPL-3.0** (see `LICENSE`). If you share the APK, keep the source available.
