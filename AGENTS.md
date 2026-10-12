# MF800 4G WiFi Modem — Custom Web UI & SMS/USSD Helper

Repo ini berisi hasil modifikasi & temuan untuk modem **MF800** (varian UZ801, chipset
Qualcomm MSM8916, Android 4.4.4 KitKat). Dua bagian utama:

- `web/` — web root admin UI modem (hasil modifikasi, state live di device)
- `apps/` — artefak sisi Android/APK:
  - `apps/mifi-helper/` — helper APK kustom (HTTP API SMS/USSD) yang terpasang di modem
  - `apps/hotspot/` — APK "WiFi Modem": jadikan HP Android hotspot yang bisa dikontrol
    dari UI native + QS tile + HTTP API/web, **tanpa root** (automasi AccessibilityService).
    Terverifikasi di Sony SOV36 (XZ1, Android 9); lihat `apps/hotspot/README.md`
  - `apps/mini-agent/` — agen AI mungil (Go, static arm) yang jalan di dalam modem
- `scripts/` — tooling: `mf800.sh`, build/install APK, `usb-ncm/`, `root-shell/`, `alpine-chroot/`

> **Tidak disertakan di repo (disimpan lokal di luar repo):** image firmware (`*.img`,
> berisi IMEI di `modemst1/2.img` dan kredensial WiFi + MAC di `persist.img`),
> source decompile `apps/mifiservice-decompiled/`, PDF manual OEM, dan aset web OEM
> hasil ekstraksi. Lihat `README.md` → "What is not in this repo".

---

## Device & Akses

| Item | Nilai |
|---|---|
| Model | MF800 (build Z85/N958St, China) |
| Firmware | V3.3.3 (baseband N958St_Z85_CN) |
| OS | Android 4.4.4 KitKat, root (adbd = uid 0) |
| Chipset | Qualcomm MSM8916 (Snapdragon 410) |
| RAM / storage | 398 MB / eMMC ~3 GB |
| Admin web | http://192.168.100.1 (login `admin`/`admin`) |
| Web root di device | `/data/data/com.mifiservice.hello/files/jetty2` |
| Web server | embedded Jetty di dalam APK `MifiService.apk` (com.mifiservice.hello) |
| Helper API | http://192.168.100.1:8080 (APK `com.mifi.helper`) |

### Akses cepat
```bash
ADB=~/Library/Android/Sdk/platform-tools/adb
$ADB devices                      # serial 0123456789ABCDEF (root)
$ADB shell                        # uid=0(root)
# forward ke API web & helper:
$ADB forward tcp:18081 tcp:80     # web admin
$ADB forward tcp:18080 tcp:8080   # mifi-helper
curl http://localhost:18081/...   # web
curl http://localhost:18080/api/status
```
> Jika Mac di WiFi rumah (192.168.18.x) tak bisa akses 192.168.100.1 — pakai
> `adb forward` di atas. WiFi modem = `192.168.100.x`, SSID/pass default "Waifai"/"kamurindu".

---

## Arsitektur

```
Browser (pengguna)
   │  HTTP :80 (Jetty di MifiService.apk)
   ▼
<web root di /data/data/com.mifiservice.hello/files/jetty2>
   ├─ index.html   (login, session)
   ├─ main.html    (shell: header + menu + iframe #mainifr)
   ├─ *.html       (halaman fitur: status, netsel, sms, ussd, ...)
   ├─ css/         (main.css, common.css, layer.css + restu-v6.css ← design system kami)
   ├─ js/          (jquery, index.js, main.js, utils/ajax.js, ...)
   └─ img/
   │
   │  HTTP :8080
   ▼
com.mifi.helper (APK, port 8080)
   ├─ HttpApiService  (REST: SMS list/get/send/delete, USSD trigger, status, battery)
   ├─ UssdAccessibilityService (baca dialog USSD → teks respons)
   └─ BootReceiver (auto-start saat boot)
```

**Dua backend terpisah:**
1. `:80` — Jetty di dalam `MifiService.apk` (Java), endpoint `/ajax` JSON dengan `funcNo`.
2. `:8080` — helper APK kami, REST `/api/*`, dipanggil dari halaman sms/ussd.

---

## Endpoint `/ajax` (web admin, funcNo) — penting

Via `POST http://192.168.100.1/ajax`, body `{"funcNo":N, ...note.json}`.

| funcNo | Fungsi |
|---|---|
| 1000 | Login (`{"funcNo":1000,"username":"admin","password":"admin"}`) |
| 1001 | Status (`results[0].netmode/netstatus/rssi/oper/net_mode`) |
| 1002 | Get WiFi/DHCP info |
| 1004 | Connect/Disconnect (`conn_mode:1`) |
| 1005 | **Set network mode (RAN)** `{"funcNo":1005,"net_mode":"0|1|2"}` → Auto/3G Only/4G Only |
| 1006–1018 | WiFi, DHCP, reboot(1013), factory(1014), SIM, APN |
| 1029 | Device info (fwversion, imei) |
| 2000 | Reboot bootloader |
| 2001 | Enable ADB (usbdebug.html) |

> ❗ **USSD = dead end (verifikasi 2026-09, lihat `docs/ussd-csfb-analysis.md`)**: modem
> me-release USSD instan di LTE (konfigurasi produk data-only/SMS-only, mesin CSFB ada
> tapi tak pernah dijalankan). Workaround lama "net_mode=1 (3G Only)" SUDAH MATI karena
> 3G dimatikan nasional; scan `AT+COPS=?` menunjukkan semua operator di area ini LTE-only.
> Gunakan jalur SMS via mifi-helper untuk fungsi operator.

## Channel AT command (temuan penting)

Binary `/system/bin/modem_at <AT_CMD>` (root) = pintu AT ke modem — dipakai internal
`MifiService.apk` (`WanDataController.sendAtBySmd`). Contoh:

```bash
$ADB shell 'modem_at AT^SYSCONFIG?'    # mode RAT: 11=Auto, 2=WCDMA only, dst (set valid {2,11,12,14})
$ADB shell 'modem_at AT+COPS=?'        # scan PLMN (30-120 dtk); AcT: 0=GSM, 2=UTRAN, 7=E-UTRAN
```

> **Bug terminal lama**: `/api/sh` selalu `exec su -c sh`, jadi gagal
> `su: setuid failed` (lihat bagian mifi-helper). Sudah diperbaiki → fallback uid app.

---

## Internet via USB ke macOS (temuan penting)

Modem meng-expose jaringan USB sebagai **RNDIS**, dan **macOS tidak punya driver
RNDIS** → tak ada interface jaringan di Mac. Kernel modem mendukung **CDC-NCM**
(yang didukung native macOS); komposisi `ncm,adb` ada di `init.qcom.usb.rc`.

Solusi: switch gadget ke NCM saat runtime lewat ADB, lalu bridge `ncm0` ke `br0`
(dnsmasq + NAT OEM langsung melayani Mac). **Tidak dipersist** (UsbDeviceManager
menimpa `ncm` saat boot; dan ganti komposisi saat `adbd` hidup memicu bug functionfs
yang memutus ADB). Default tetap RNDIS supaya ADB stabil.

```bash
./scripts/usb-ncm/enable-usb-ncm.sh      # manual
./scripts/usb-ncm/install-agent.sh       # launchd: auto-enable saat modem tak reachable
```
Saat NCM aktif: Mac dapat `192.168.100.x`, default route via modem, egress = IP
seluler. Mac lalu bisa langsung akses `http://192.168.100.1` & `:8080` (tak perlu
`adb forward`). Detail: `docs/usb-ncm-macos.md`.

---

## Endpoint mifi-helper (`:8080`)

| Route | Fungsi |
|---|---|
| `GET /api/status` | model, android, service up (tanpa auth) |
| `GET /api/battery` | baterai asli (tanpa auth): `{percent,level,scale,charging,status,plugged,voltage_mv,temperature_c,source}` |
| `POST /api/auth/login {pin}` | validasi PIN (default `admin`) |
| `POST /api/auth/pin {old,next}` | ganti PIN (4-32 char, butuh PIN lama) |
| `GET /api/sms/list?box=inbox&limit=N` | daftar SMS (inbox/sent/draft/outbox) |
| `GET /api/sms/get?id=&box=` | satu SMS |
| `POST /api/sms/delete {id,box}` | hapus SMS |
| `POST /api/sms/send {address,body}` | kirim SMS (SmsManager) |
| `POST /api/ussd {code}` | kirim code & tunggu respons dialog (≤15s) |
| `POST /api/sh {cmd,cwd,timeout}` | jalankan shell (uid app, non-PTY) untuk `terminal.html` |
| `GET /api/pty/info` | cek dukungan PTY (`{available,transport,endpoint}`) |
| `GET /api/pty?pin=&cols=&rows=` (WS) | **terminal interaktif sungguhan** via PTY (WebSocket) |

**Terminal interaktif (PTY WebSocket).** `/api/pty` meng-upgrade ke WebSocket
(RFC 6455, hand-rolled di `HttpApiService`), lalu menjembatani ke sebuah **PTY
sungguhan** (`libmifipty.so`, lihat bawah). Frame **binary** = byte mentah
(keystroke/keluaran), frame **text** = kontrol JSON (`{"resize":[cols,rows]}`).
Auth lewat `?pin=` (browser tak bisa set header kustom pada WebSocket). `tty`
mengembalikan `/dev/pts/N`, `stty size` mengikuti ukuran xterm, resize otomatis
(lib meng-`ioctl(TIOCSWINSZ)`, kernel kirim SIGWINCH), dan `vi`/`top`/REPL jalan
sungguhan. Shell dijalankan `/system/bin/sh -i` dengan `HOME=<filesDir>/home`
(sekalian `chdir` ke situ), `TERM=xterm-256color`, PATH seperti `shellPath()`.
`terminal.html` default ke mode ini; ada tombol fallback ke mode `POST /api/sh`
bila `Pty.available()` false (mis. .so gagal dimuat) atau WS gagal dibuka.

**Terminal (`/api/sh`) jalan sebagai user biasa.** `su` ada tapi SELinux menolak
(`su: setuid failed: Operation not permitted`), jadi `runShell` men-probe `su`
sekali dan **fallback ke `/system/bin/sh` sebagai uid app** (`u0_a30`). Perintah
tanpa root tetap banyak yang jalan (`ls`, `ps`, `df`, `netcfg`, `getprop`,
`logcat`, bahkan `modem_at AT+CSQ` — `/dev/smd11` world-rw). Marker rc memakai
`echo` (build ini tidak punya `printf` native; tersedia via BusyBox).

**BusyBox: toolbox Android 4.4 minim** — `awk`, `sed`, `head`, `tail`, `find`,
`free`, `sort`, `uniq`, `cut`, `tr`, `xargs`, `which`, `tar`, `base64`, `md5sum`,
`printf`, `uname`, `hostname`, `whoami`, dll **tidak ada** di `/system/bin`.
Firmware ini menyertakan **BusyBox v1.31.0** di `/system/bin/busybox` tapi tanpa
symlink applet. `installBusybox()` (dipanggil saat service start) membuat symlink
applet di `<filesDir>/bin` (tanpa root) dan menaruh dir itu **di akhir PATH**
(`/sbin:/system/sbin:/system/bin:/system/xbin:<filesDir>/bin`), jadi tool native
tetap menang dan yang hilang jatuh ke BusyBox. Verifikasi: `cmd="whoami; awk ..."`.
`whoami` di-shim: symlink BusyBox-nya diganti skrip kecil yang menurunkan nama
dari `id` (BusyBox `whoami` gagal resolve uid → "unknown uid 10030"); ini berlaku
di mode perintah maupun shell PTY (yang tak mewarisi shell-function di `runShell`).
Setelah mengubah logika `installBusybox`, hapus `<filesDir>/bin/.installed`
(atau seluruh `<filesDir>/bin`) agar dipasang ulang.

**PTY native (`libmifipty.so`).** Bionic Android **tidak punya `openpty`/`forkpty`**
(simbolnya tidak ada di `/system/lib/libc.so`), jadi `src/com/mifi/helper/mifi_pty.c`
mengalokasi PTY sendiri dari `/dev/ptmx` (`TIOCSPTLCK` unlock, `TIOCGPTN`, set
`TIOCSWINSZ`, `fork`+`setsid`+`TIOCSCTTY`+`dup2`, `execve /system/bin/sh -i`).
NDK r27 hanya menyediakan header API 21+; lib ini cuma memakai antarmuka libc/kernel
kuno sehingga `.so` hasilnya tetap ter-load di API 19 (terbukti `available:true`).
`Pty.java` = wrapper JNI; metode: `nativeSpawn/nativeRead/nativeWrite/nativeResize/nativeClose`.
Build include lib ini lewat `scripts/build-helper.sh` (tidak lagi serba manual).

**Bug script-via-stdin (fix 2026-09-25).** Dulu `execScript` menyalurkan skrip
lewat **stdin** `sh` (`os.write(script)`), jadi perintah yang membaca stdin
(`cat`, `read`, `more`, `vi`, `wc` tanpa file) menelan sisa skrip — termasuk
baris marker `__MIFI_RC__` — sehingga `rc`/`cwd` jadi ngawur (mis. `rc="$(pwd)"`)
dan perintah interaktif rusak. Sekarang jalur non-root memakai
`sh -c '<script>'` (skrip sebagai argv) + prefix `exec 0</dev/null`, jadi stdin
perintah = `/dev/null` (EOF) dan marker tak pernah termakan. Jalur `su` masih
lewat stdin (su di device ini toh mati). Frontend (`terminal.html`) men-strip
urutan alt-screen `\x1b[?(1049|1047|47)[hl]` dari output agar app full-screen
(`vi`) tetap tampil di buffer normal; **jangan** mengirim `\x1b[?1049l` saat
tidak di alt-screen — xterm malah restore cursor tersimpan dan menimpa baris
aktif (ini bug yang sempat muncul). Sebelum prompt dikirim `\x1b[0m\x1b[?25h`
saja (reset SGR + pastikan kursor tampak).

**`speedtest` (Ookla speedtest.net) di terminal (sejak 2026-10).** Engine
`showwin/speedtest-go` dibangun sebagai binary **static linux/arm** (Go,
`CGO_ENABLED=0`) — bionic Android 4.4 tak punya glibc, dan binary static tak
butuh libc/linker sama sekali. Terpasang di `<helper filesDir>/bin` (sudah ada di
PATH terminal) sebagai `speedtest` (wrapper) + `speedtest-go` (engine), owner app
uid `u0_a30` ⇒ persisten setelah reboot. **Gotcha TLS:** binary `GOOS=linux` Go
**tidak** membaca CA store Android — `/system/etc/security/cacerts` hanya ikut saat
`GOOS=android`, yang justru butuh cgo + simbol API 21 (`sigfillset`) yang tak ada di
API 19 ⇒ load gagal. Solusi: wrapper mengekspor
`SSL_CERT_DIR=/system/etc/security/cacerts` sebelum exec (tanpa itu:
`x509: certificate signed by unknown authority`). **DNS aman**: karena tak ada
`/etc/resolv.conf`, resolver Go default ke `127.0.0.1:53` (dnsmasq modem). Pasang
ulang: `./scripts/install-speedtest.sh` (butuh Go 1.26+; set `ANDROID_SERIAL=...`
bila >1 device terhubung).

**Auth (sejak 2026-09)**: semua `/api/*` kecuali `/api/status`, `/api/battery` & `/api/auth/*`
wajib header `X-Auth: <PIN>`. PIN disimpan di SharedPreferences helper
(`/data/data/com.mifi.helper/shared_prefs/auth.xml`); 5x gagal → lockout 5 menit
(request salah apa pun ikut menghitung). Frontend memakai `web/js/auth.js`
(overlay PIN, simpan di localStorage `mifi_pin`, set `$.ajaxSetup` X-Auth otomatis).
Lepas lockout manual: `adb shell "rm /data/data/com.mifi.helper/shared_prefs/auth.xml"`
lalu restart service.

SMS baca/kirim **terbukti berfungsi** (tes kirim ke nomor uji & terkirim).
USSD pipeline **berfungsi** (dialog terbaca via accessibility) tapi dibatasi firmware CSFB.

---

## Panel demo interaktif: `scripts/mf800.sh` (bahan Lo-Tech Meetup)

Satu skrip untuk demo live yang mengikuti alur slide `slides/ngoprek-modem.html`
(JogjaDevs · Lo-Tech Meetup, 2 Okt 2026): **deteksi → switch mode → dump → deploy**.
Tanpa dependensi selain `adb` (dan `curl` optional). Menu interaktif atau subcommand:

```bash
./scripts/mf800.sh                 # menu interaktif
./scripts/mf800.sh detect          # model/root/RAN/sinyal/operator/partisi
./scripts/mf800.sh mode auto|3g|4g # RAN via AT^SYSCONFIG (12/14/11) + settings
./scripts/mf800.sh debug [--yes]   # aktifkan ADB/diag (funcNo 2001 + reboot)
./scripts/mf800.sh dump            # snapshot partisi firmware → ./dumps/ (+ sha256)
./scripts/mf800.sh deploy          # = scripts/deploy-web.sh
./scripts/mf800.sh web             # adb forward :80 & :8080 + URL
./scripts/mf800.sh ncm             # internet USB CDC-NCM ke macOS
./scripts/mf800.sh helper          # build + install mifi-helper.apk
```

Catatan device yang sudah di-hardcode (hasil uji 2026-10):

- **Tabel partisi ada di `/dev/block/bootdevice/by-name`**, bukan `/dev/block/by-name`.
  `resolve_byname()` mencoba kedua lokasi + glob `platform/*/*/by-name`.
- **`adbd` Android 4.4 tidak mendukung `adb exec-out`** (`error: closed`). Dump karena
  itu memakai **`adb pull <blockdev> <file>`** (bit-exact, streaming, tak perlu ruang
  `/data`); `exec-out` dan `dd`+pull tetap ada sebagai fallback.
- **`adb shell` meneruskan stdin skrip ke device** → semua panggilan non-interaktif
  di `cmd()` diberi `</dev/null`, kalau tidak jawaban prompt (`y`) "dimakan" adb.
- **toolbox 4.4 tanpa `tail`/`head`/`uname`** → kernel dibaca dari `/proc/version`,
  baris `df`/`AT` di-parse pakai `awk`/`grep`/`sed` (BusyBox belum tentu tersymlink di
  shell non-PTY).
- **`dump` default menyertakan partisi firmware saja** (lewati `userdata` & `cache`
  yang besar/dinamis → dari 3.5 GB jadi ±960 MB). Pakai `--all` untuk penuh,
  `--only modem,modemst1` untuk sebagian, `--force` untuk menimpa.
- **Dump butuh daya cukup.** Baterai modem lemah (±4%) + I/O USB bisa bikin device
  mati di tengah dump (gejala: beberapa partisi kecil sukses lalu sisanya "gagal").
  Skrip kini cek `<25%` sebelum jalan, menghentikan dump begitu `device_alive` gagal
  (bukan spam "gagal"), menampilkan error adb asli, dan **resume** — file yang sudah
  ada dilewati. Isi daya dulu, lalu ulangi `./scripts/mf800.sh dump`.

### Bootstrap: modem mode normal → debug mode (aktifkan ADB)

"Debug mode" OEM = **funcNo 2001**: `AjaxSevlet` memanggil
`WanDataController.setDebugMode()` → kirim `AT+USB=1,268968726` → lalu
`DeviceController.rebootDevice()`. Setelah boot, ADB root & port diag hidup.
Di device ini ADB sudah permanen, jadi biasanya tak perlu diulang.

`/ajax` **tidak memerlukan cookie/session** (login hanya gating UI), jadi cukup satu POST:

```bash
# dari host yang menjangkau modem LAN (WiFi modem; macOS tak melihat RNDIS):
curl -X POST http://192.168.100.1/ajax -d '{"funcNo":2001}'
# → {"flag":"1",...} lalu modem reboot. Tunggu, cek: adb wait-for-device && adb devices
```

Lewat panel: `./scripts/mf800.sh debug` (atau tombol `d` di menu) — otomatis pakai
ADB forward bila device sudah terlihat, atau `MF800_HOST=<ip> ./scripts/mf800.sh debug`
bila host menyambung via WiFi modem. Setara via UI: buka `http://192.168.100.1/usbdebug.html`
(halaman itu auto-POST funcNo 2001 saat dimuat → modem reboot). Kalau sudah root,
alternatif tanpa HTTP: `modem_at AT+USB=1,268968726` lalu `reboot`.
Baca status: funcNo **1021** (`{"funcNo":1021}` → `mode:1` = ADB aktif).

## Menyalin (deploy) perubahan ke device

Web root di `/data` writable (root). **File WAJIB `chown root:root` + `chmod 755`**
(BUKAN `system:system`/`600` — lihat gotcha #5). Cara cepat — pakai script:

```bash
./scripts/deploy-web.sh    # push semua file web/ + chown root:root + chmod 755
```

Manual:
```bash
ADB=~/Library/Android/Sdk/platform-tools/adb; J=/data/data/com.mifiservice.hello/files/jetty2
$ADB push ./web/main.html $J/main.html
$ADB push ./web/css/restu-v6.css $J/css/restu-v6.css
$ADB shell "cd $J && chown root:root main.html css/restu-v6.css && chmod 755 main.html css/restu-v6.css"
```
> Toolbox `chown` di device tidak punya `-R`; untuk rekursif pakai loop glob
> (lihat `scripts/deploy-web.sh`). `chmod -R` tersedia.

> ⚠️ **Cache browser**: file HTML/CSS di-cache agresif. Untuk jaminan perubahan kelihatan,
> ganti nama file CSS (mis. `restu-v6.css` → `restu-v7.css`) lalu update referensinya, ATAU
> arahkan user hard-refresh (Cmd+Shift+R).

---

## Membangun & menginstal mifi-helper APK

Manifest `apps/mifi-helper/AndroidManifest.xml`, source di `apps/mifi-helper/src/com/mifi/helper/`.
Build tanpa Gradle — **pakai script** (sudah termasuk kompilasi native `libmifipty.so`
via NDK; lihat catatan PTY di atas):

```bash
./scripts/build-helper.sh          # -> apps/mifi-helper/out/mifi-helper.apk
```

Script setara dengan langkah manual berikut (kalau perlu debug):
```bash
SDK=~/Library/Android/Sdk; BT=$SDK/build-tools/34.0.0; JAR=$SDK/platforms/android-28/android.jar
NDK=$SDK/ndk/27.1.12297006; TC=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin
P=./apps/mifi-helper; mkdir -p $P/obj $P/out/lib/armeabi-v7a $P/gen
$TC/armv7a-linux-androideabi21-clang -shared -fPIC -O2 \
  -o $P/out/lib/armeabi-v7a/libmifipty.so $P/src/com/mifi/helper/mifi_pty.c
$BT/aapt package -f -m -M $P/AndroidManifest.xml -S $P/res -I $JAR -J $P/gen
javac --release 8 -nowarn -cp $JAR -d $P/obj $P/gen/com/mifi/helper/R.java $P/src/com/mifi/helper/*.java
$BT/d8 --release --min-api 19 --lib $JAR --output $P/out $P/obj/com/mifi/helper/*.class
$BT/aapt package -f -M $P/AndroidManifest.xml -S $P/res -I $JAR -F $P/out/base.apk
(cd $P/out && $BT/aapt add base.apk classes.dex \
  && $BT/aapt add base.apk lib/armeabi-v7a/libmifipty.so \
  && $BT/zipalign -f 4 base.apk aligned.apk)
$BT/apksigner sign --ks $P/debug.keystore --ks-pass pass:android --min-sdk-version 19 \
  --out $P/out/mifi-helper.apk $P/out/aligned.apk   # keystore: keytool -genkeypair ...
```

Instal + aktifkan:
```bash
$ADB install -r $P/out/mifi-helper.apk
ADB shell settings put secure enabled_accessibility_services com.mifi.helper/.UssdAccessibilityService
ADB shell settings put secure accessibility_enabled 1
ADB shell am startservice com.mifi.helper/.HttpApiService
```

---

## Design system: `web/css/restu-v6.css`

Satu file CSS dimuat **terakhir** di SEMUA halaman (diinject `<link>` sebelum `</head>`).
Menimpa legacy `css/main.css`/`common.css`. Token:
`--green:#8dc63f; --green-dark:#5a9e1f; --ink:#26321f; --bg:#f2f6ec; --line:#dfe8d3`.

Penanganan layout shell (main.html): `#menu` float kiri 218px, `#main` margin-left 230px
(bukan flex — flex bermasalah). Responsive: `<960px` menu jadi bar horizontal.
Konten `.content table` dibuat `width:auto` (shrink-to-fit) supaya label mepet ke nilai.

---

## Gotcha penting (dari pengalaman)

1. **`lang="en"` di `<html>` merusak halaman!** `jsonHandler.js` mentranslate SEMUA elemen
   ber-atribut `[lang]`; tag `<html lang="en">` membuat seluruh dokumen ketimpa jadi teks "en".
   Jangan pernah pasang `lang` di `<html>`.
2. **File asli charset gb2312** (index, main, & konten lama) — jangan decode tulis ulang dgn
   UTF-8 sembarangan; pakai operasi **byte** (python `open(...,'rb').read()`).
3. **Template Jetty `%{eval:...}%` / `%{if:...}%`** diproses server-side; kalau rewrite halaman,
   pertahankan tag ini verbatim (umumnya di dalam komentar HTML).
4. **ID elemen wajib dipertahankan** saat rewrite (JS bind by id): `#select,#en,#zh,#frm,#login`,
   status bar (`#operator,#signal_grid,#signalStrength,#network1,#operation,#batteryCharging,#activate`),
   menu (`#body1..#body10,#advanced,#mifi_wifi_menu,#helpEn,#HelpZh`), iframe `#mainifr`.
5. **Reboot re-extract — SUDAH DIATASI (verifikasi reboot 2026-09-04)**: `JServer.start()`
   (`JServer.java:40`, lihat `apps/mifiservice-decompiled`) memanggil `extractAssets()` yang
   **menimpa semua file** di `jetty2/` dengan versi dari `assets/jetty2/` MifiService.apk
   **setiap kali Jetty start** (tiap boot, tanpa cek versi). Solusi: deploy file kita sebagai
   `root:root` + mode `755` — penimpaan oleh uid `system` gagal *silent* (FileNotFoundException
   ter-catch di `JServer.java:117`, service tidak crash), Jetty tetap bisa serve (world-readable).
   **File khusus kita** (sms/ussd/restu-v6.css/auth.js/battery.js) memang tidak ada di assets, jadi tak
   pernah tersentuh. Terbukti: reboot penuh → file utuh, login kustom + css + sms.html 200.
   Caveat: factory reset (funcNo 1014) menghapus `files/` → jalankan `./scripts/deploy-web.sh` lagi.
   Helper APK auto-start saat boot (terbukti), aksesibilitas persist.
6. **Firmware dump** tersimpan di luar repo (`~/mf800-firmware-private/`: `system.img`,
   `boot.img`, `modem.img`, `aboot.img`, dst) + `modemst1/2.img` (**IMEI**/calib) dan
   `persist.img` (kredensial WiFi + MAC) — **jangan pernah di-commit**. Jangan flash
   firmware beda varian — bisa brick.
7. **ADB diaktifkan** via funcNo 2001 (usbdebug) — sudah permanen di device ini.

---

## File yang kami buat/modifikasi (garis besar)

- `web/index.html` — rewrite login page (modern, valid HTML, tanpa `lang="en"`)
- `web/main.html` — rewrite shell (header flex + menu + iframe), badge header;
  status bar memuat pil baterai (`data-mifi-battery-box`, diisi `js/battery.js`)
- `web/sms.html` — baru; manajemen SMS (list/send/delete, auto-refresh, responsive card)
- `web/ussd.html` — baru; kirim USSD + tampilkan respons
- `web/terminal.html` — baru; terminal web: mode **PTY interaktif** (WebSocket ke
  `/api/pty`, xterm.js penuh: vi/top/REPL, resize, Ctrl+C) + mode fallback
  "perintah" (satu exec per perintah via `/api/sh`, editor baris sendiri)
- `web/status.html` — halaman Home; blok **Battery** (persen, status, tegangan,
  suhu) via `js/battery.js`
- `web/css/restu-v6.css` — design system + override legacy (v6: pil baterai di
  status bar + bar di halaman status; angka dari `/api/battery`. v5: visual polish —
  login gradient fixed, `boxbot` stray rule dihapus, section divider di kartu,
  notis `.warn` jadi amber (bukan merah), label column hanya untuk label asli
  (`td:first-child:not(:empty)`) sehingga spacer tak lagi dapat 120px, styling
  radio/checkbox/file, divider status jadi hijau lembut, tombol Apply sebaris
  dengan select. v4 sebelumnya: responsive pass —
  submenu Advanced via `.open` + `js/utils/childmenu.js`, iframe auto-height dari
  `main.html` (inline shell script), tabel legacy di-stack `<560px`, touch target 44px,
  input 16px anti iOS-zoom, `div.block` max-width 980px, semua halaman punya viewport meta)
- `apps/mifi-helper/src/...` — HttpApiService (auth PIN + lockout, `/api/sh` non-root,
  `/api/battery` dari BatteryManager (fallback sysfs), `/api/pty/info` + WS `/api/pty`
  + BusyBox/PTY setup): `mifi_pty.c` (PTY native), `Pty.java`
  (wrapper JNI), UssdAccessibilityService, BootReceiver
- `scripts/build-helper.sh` — build APK + `libmifipty.so` (tanpa Gradle)
- `scripts/install-speedtest.sh` — build (static linux/arm) + pasang `speedtest`/`speedtest-go`
  ke terminal modem (lihat catatan `speedtest` di atas)
- `scripts/build-hotspot.sh` — build `hotspot.apk` (apps/hotspot, tanpa Gradle)
- `scripts/test-hotspot.sh` — install + `adb forward` + uji endpoint API hotspot
- `scripts/usb-ncm/` — fix internet USB macOS: `enable-usb-ncm.sh` (switch gadget ke
  NCM + bridge `ncm0`), `auto-enable.sh` + `install-agent.sh`/`uninstall-agent.sh`
  (agent launchd), lihat `docs/usb-ncm-macos.md`
- `~/mf800-oem-private/apps/mifiservice-decompiled/` — hasil jadx 1.5.6 dari
  `MifiService.apk` V3.3.3 device ini (tidak disertakan di repo publik; peta kelas &
  referensi silang: repo komunitas danyaPostfactum/MifiService untuk varian UZ801 ~V3.4.x)
- `web/js/auth.js` — gerbang PIN bersama untuk halaman kustom (sms/ussd/terminal nanti)
- `web/js/battery.js` — widget baterai: poll `GET :8080/api/battery` (20 dtk) dan isi
  elemen ber-atribut `data-mifi-battery` / `-box` / `-bar`; gagal-diam saat helper
  tak terjangkau. Helper menandai `.charging`/`.low`/`.critical`/`.offline`
- `apps/mifi-helper/AndroidManifest.xml`, `res/`, `out/mifi-helper.apk` (built)
- `apps/hotspot/` — APK "WiFi Modem" (lihat `apps/hotspot/README.md`)

---

## App hotspot ("WiFi Modem") — hotspot Android tanpa root

APK terpisah dari mifi-helper, untuk menjadikan **HP Android** sebagai hotspot yang
bisa dikontrol dari app/QS tile/HTTP API. Terverifikasi di **Sony SOV36 (Xperia XZ1,
Android 9), TANPA root**.

```bash
./scripts/build-hotspot.sh                 # -> apps/hotspot/out/hotspot.apk
./scripts/test-hotspot.sh QV7005FB11       # install + forward + uji API (port device 8090)
```

### Kenapa tanpa root tetap bisa
App biasa dilarang menyalakan hotspot: `setWifiApEnabled()` (Android 4–9) dijaga
`NETWORK_SETTINGS` dan `startTethering()` (8+) dijaga `TETHER_PRIVILEGED` — keduanya
`signature|privileged` **dan tidak ada di SDK publik** (jadi harus refleksi).
Solusinya rantai 4 backend (`HotspotEngine`):
1. `privileged` — refleksi `setWifiApEnabled` (app privileged)
2. `root-app_process` — `su -c app_process … TetherCli` (uid 0)
3. **`accessibility`** — `HotspotAccessibilityService` membuka Setelan lalu mengklik
   sakelar hotspot (cek `isChecked()` dulu) ← **ini yang dipakai di HP tanpa root**
4. `settings` — hanya membuka layar tethering

### Fakta device (dump UI Sony SOV36 — dipakai `TetherScreens.java`)
| Hal | Nilai |
|---|---|
| Layar hotspot | `com.sonymobile.wifi/.hotspot.WifiApSettings` |
| Sakelar | `com.sonymobile.wifi:id/switch_bar_switch` (checkable, clickable) |
| Dialog konfirmasi | tidak ada |
| `android.settings.WIFI_TETHERING_SETTINGS` | **tidak resolve** → wajib component name |
| Jebakan | ada `android:id/switch_widget` lain ("Make network name discoverable") — jangan pakai id generik bila id Sony ada |
| Layar AOSP | `com.android.settings/.Settings$TetherSettingsActivity`; baris "Portable hotspot" tanpa switch → harus diklik untuk masuk |

**SSID asli tidak bisa dibaca tanpa root** (`getWifiApConfiguration` dijaga NETWORK_SETTINGS).
App memanen teks `"Network name: <SSID>"` dari layar hotspot lewat automasi — tombol
"Baca nama WiFi dari HP" / `POST /api/ssid/read` → tersimpan sebagai `ssidSystem`
(status juga mengembalikan `ssidApp`). Terverifikasi membaca `KodeSpace Labs`
(sementara nilai app masih default `Mifi-SOV36`).

Aktifkan otomasi (sekali): app → "Aktifkan layanan aksesibilitas" → centang **WiFi Modem**.
Via adb: `settings put secure enabled_accessibility_services com.mifi.hotspot/com.mifi.hotspot.HotspotAccessibilityService`
+ `settings put secure accessibility_enabled 1`. Event dibatasi ke paket Setelan
(`accessibility_config.xml`) dan hanya bergerak saat ada permintaan aktif.

Uji yang sudah lolos di device: `POST /api/hotspot/start` → switch `ON` di layar
Setelan + auto-back ke app; `POST /api/hotspot/stop` → switch `OFF`.

### Gotcha yang ketemu saat membangun (berguna untuk APK berikutnya)
1. **`layout_width`/`layout_height` TIDAK BOLEH ada di `<style>`** — LayoutInflater
   mengabaikannya → crash `You must supply a layout_width attribute`. Layout param
   selalu inline; style hanya untuk background/padding/warna/teks.
2. **`android:windowBackground` tidak menerima literal `#RRGGBB`** — harus `@color/...`.
3. API tethering adalah `@SystemApi` → tidak ada di `android.jar`; **semua lewat refleksi**
   (`ApApi.java`). Konsekuensi: di Android 10+ `setWifiApEnabled` hilang → jatuh ke accessibility.
