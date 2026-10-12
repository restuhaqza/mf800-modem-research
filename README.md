# MF800 4G Modem — Reverse Engineering & Custom Firmware Research

Research notes, tooling, and modified software for the **MF800** (UZ801-class)
4G USB/WiFi modem — a Qualcomm **MSM8916** device running **Android 4.4.4 KitKat**.

This repo documents how a cheap, locked-down data-only modem was opened up: a
custom web UI, an SMS/USSD HTTP API, a real interactive terminal, a no-root
hotspot app for the *other* end of the link, and a pile of hard-won gotchas.

> ⚠️ **Nothing here ships proprietary firmware or device identity data.** See
> [What is *not* in this repo](#what-is-not-in-this-repo).

---

## ▶️ Watch the explainer

[![MF800 — reverse engineering a locked 4G modem (67-second explainer)](media/preview.gif)](media/mf800-explainer.mp4)

*A 67-second explainer — the device, the problem, what we built, and the findings.
**[▶ Watch in 1080p](media/mf800-explainer.mp4)***

---

## TL;DR — what got built

| Thing | What it does |
|---|---|
| **Custom admin web UI** (`web/`) | Rewritten login/shell + new SMS, USSD, Terminal and Status pages for the modem's embedded Jetty server |
| **`mifi-helper` APK** (`apps/mifi-helper/`) | REST API on `:8080` for SMS read/send/delete, USSD, battery, and a **real PTY-backed terminal** (`vi`, `top`, REPLs) over WebSocket |
| **`hotspot` APK** (`apps/hotspot/`) | Turn an **unrooted** Android phone *into* a controllable hotspot via AccessibilityService automation |
| **`mini-agent`** (`apps/mini-agent/`) | A tiny Go "coding agent" that runs *inside* the 398 MB modem and calls an LLM (DeepSeek) with shell/file tools |
| **`scripts/mf800.sh`** | One-panel demo: detect → switch network mode → dump partitions → deploy web |
| **`scripts/root-shell/`** | Persistent uid-0 PTY channel reachable from the web terminal |
| **`scripts/alpine-chroot/`** | Alpine Linux + `apk` package manager inside the modem via `chroot` |

Conference slides: `slides/ngoprek-modem.html` (JogjaDevs · Lo-Tech Meetup).

## The device

| Item | Value |
|---|---|
| Model | MF800 (build Z85/N958St) |
| Firmware | V3.3.3, baseband `N958St_Z85_CN` |
| SoC | Qualcomm MSM8916 (Snapdragon 410) |
| OS | Android 4.4.4 KitKat — rooted, `adbd` runs as uid 0 |
| RAM / storage | 398 MB / ~3 GB eMMC |
| Admin UI | `http://192.168.100.1` (embedded Jetty inside `MifiService.apk`) |
| Helper API | `http://192.168.100.1:8080` (the `mifi-helper` APK) |

### Architecture

```
Browser ──HTTP :80──► Jetty (inside MifiService.apk)
                       └─ web root: /data/data/com.mifiservice.hello/files/jetty2
                          index.html · main.html · sms.html · ussd.html · terminal.html
                          css/restu-v6.css · js/auth.js · js/battery.js
Browser ──HTTP :8080─► com.mifi.helper (our APK)
                       ├─ HttpApiService          REST: SMS, USSD, battery, /api/sh, WS /api/pty
                       ├─ UssdAccessibilityService reads USSD dialogs
                       └─ BootReceiver            autostart
```

## Quick start

```bash
ADB=~/Library/Android/Sdk/platform-tools/adb

$ADB devices                       # serial 0123456789ABCDEF (root)
$ADB forward tcp:18081 tcp:80      # admin web
$ADB forward tcp:18080 tcp:8080    # mifi-helper
curl http://localhost:18081/
curl http://localhost:18080/api/status

./scripts/mf800.sh                 # interactive demo panel
./scripts/deploy-web.sh            # push web/ → device + lock down perms
./scripts/build-helper.sh          # build mifi-helper.apk (+ native PTY lib)
./scripts/build-hotspot.sh         # build hotspot.apk
./scripts/build-mini-agent.sh      # build the Go agent (static linux/arm)
```

Building the APKs needs only the Android **SDK + NDK** (no Gradle — see
`AGENTS.md` for the exact `aapt`/`javac`/`d8`/`apksigner` pipeline). The signing
keystore is *not* committed; generate one:

```bash
keytool -genkeypair -keystore apps/mifi-helper/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000
```

## Selected findings

- **The OEM web root is force-overwritten every boot.** `JServer.extractAssets()`
  re-extracts all of `jetty2/` from the APK assets on each start. Workaround:
  deploy our files as `root:root 755`, so the (uid `system`) overwrite fails
  silently while Jetty still serves them world-readable. Survives full reboot;
  a *factory reset* wipes `files/` — just redeploy.
- **USSD is a dead end on this firmware.** The modem releases USSD instantly on
  LTE (data-/SMS-only product config; CSFB machinery present but never armed) and
  national 3G is off, so the old "force 3G" workaround no longer applies. Full
  write-up in `docs/ussd-csfb-analysis.md`.
- **Bionic has no `openpty`/`forkpty`.** The interactive terminal ships its own
  PTY allocator (`libmifipty.so`) built from `/dev/ptmx` against the ancient
  libc/kernel ABI so it still loads on API 19. See `apps/mifi-helper`.
- **Android's toolbox is nearly empty** (`awk`, `sed`, `head`, `tail`, `find`… all
  missing). The helper symlinks the firmware's bundled **BusyBox** applets into
  the app-private bin dir (no root) and appends it to `PATH`.
- **macOS has no RNDIS driver**, so the modem's USB network is invisible. Switch
  the gadget to **CDC-NCM** (natively supported) and bridge it — see
  `scripts/usb-ncm/` and `docs/usb-ncm-macos.md`.
- **Hotspot without root, on Android 8+.** `setWifiApEnabled`/`startTethering`
  are `signature|privileged`; the fallback is an AccessibilityService that drives
  the Settings toggle. Verified on a Sony XZ1 (Android 9).

## Repo layout

```
web/                 custom admin UI (modified web root served by Jetty)
apps/
  mifi-helper/       our SMS/USSD/terminal helper APK (source + res + manifest)
  hotspot/           no-root "WiFi Modem" hotspot app (source + res + manifest)
  mini-agent/        in-modem LLM agent (Go source)
scripts/             mf800.sh, build-*/install-*, deploy-web.sh,
                     usb-ncm/  root-shell/  alpine-chroot/
docs/                ussd-csfb-analysis.md · usb-ncm-macos.md
slides/              conference deck + screenshots
AGENTS.md            the full engineering log (deep detail, all gotchas)
```

## What is *not* in this repo

Deliberately withheld — kept on the author's machine, never committed (enforced
by `.gitignore`):

- **Firmware images** (`*.img`: `system`, `boot`, `recovery`, `modem`, `aboot`,
  `sbl1`, `rpm`, `tz`, `hyp`, `persist`, `modemst1/2`, …) and partition dumps.
  These are proprietary OEM blobs **and** carry sensitive device data:
  `modemst1/2.img` contain the **IMEI**, and `persist.img` contains live **WiFi
  credentials and MAC addresses**. Publishing them would leak a unique device
  identity. Dump your own device with `./scripts/mf800.sh dump`.
- **OEM-copyright material**: the OEM user-manual PDFs, decompiled OEM
  `MifiService` sources, and extracted OEM web assets (redistribution isn't ours
  to grant). Pointers to how to obtain them are in `AGENTS.md`.

## License

Code in this repo is released under the [MIT License](LICENSE). This applies to
the original work only — **no OEM firmware, binaries, or assets are included**,
and none are licensed here.

## Credits

Reverse engineering, custom UI/APKs, and write-ups by **@restuhaqza**.
Decompiled-reference cross-check: community repo
[danyaPostfactum/MifiService](https://github.com/danyaPostfactum/MifiService).
