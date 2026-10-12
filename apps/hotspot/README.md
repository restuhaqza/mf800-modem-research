# hotspot — "WiFi Modem" (Android jadi hotspot, dikontrol dari app + web, TANPA root)

APK untuk menjadikan HP Android sebagai **WiFi modem (hotspot)** yang bisa
dinyalakan/dimatikan dari **UI native**, **Quick Settings tile**, dan **HTTP API +
halaman web bawaan**.

> ✅ **Sudah terbukti jalan di device nyata:** Sony SOV36 (Xperia XZ1, Android 9),
> **tanpa root**. Uji: `POST /api/hotspot/start` → hotspot benar-benar menyala
> (switch `ON` di layar Setelan), `POST /api/hotspot/stop` → benar-benar mati.

---

## Tanpa root, kok bisa?

Sejak Android 8, app biasa **dilarang** menyalakan hotspot:

| Android | API | Permission | App biasa? |
|---|---|---|---|
| 4–9 | `WifiManager.setWifiApEnabled()` | `NETWORK_SETTINGS` (signature\|privileged) | ❌ |
| 8+ | `ConnectivityManager.startTethering()` | `TETHER_PRIVILEGED` (signature\|privileged) | ❌ |
| semua | `LocalOnlyHotspot` | — | ✅ tapi **tidak** meneruskan internet |

Jadi kalau tidak mau root, satu-satunya jalan adalah **membuka layar Setelan lalu
meng-klik sakelarnya** — dan itulah yang dilakukan app ini lewat
**AccessibilityService** (`HotspotAccessibilityService`), persis pola
`UssdAccessibilityService` di mifi-helper.

### Rantai backend (dicoba berurutan)

| # | Backend | Cara | Perlu |
|---|---|---|---|
| 1 | `privileged` | refleksi `setWifiApEnabled()` dari app | app privileged |
| 2 | `root-app_process` | `su -c app_process … TetherCli` (uid 0) | root |
| 3 | **`accessibility`** | buka Setelan → klik sakelar hotspot (cek `isChecked()` dulu) | layanan aksesibilitas aktif |
| 4 | `settings` | hanya membuka layar tethering | apa saja |

Backend 3 inilah yang dipakai di HP tanpa root, dan **sudah terverifikasi**.

---

## Mengaktifkan automasi

1. Buka app → bagian **"Kontrol otomatis (aksesibilitas)"** → **Aktifkan layanan aksesibilitas**.
2. Di daftar layanan, centang **WiFi Modem** → konfirmasi **Izinkan**.
3. Status di app/halaman web berubah jadi `AKTIF — hotspot bisa dikontrol otomatis`.

Atau lewat adb (untuk uji cepat):

```bash
ADB=~/Library/Android/Sdk/platform-tools/adb
$ADB shell settings put secure enabled_accessibility_services com.mifi.hotspot/com.mifi.hotspot.HotspotAccessibilityService
$ADB shell settings put secure accessibility_enabled 1
```

Privasi: layanan ini dibatasi hanya mendengar paket Setelan
(`com.sonymobile.wifi`, `com.android.settings`) dan **hanya bergerak saat ada
permintaan aktif** dari tombol/tile/API — tidak pernah spontan.

---

## Fakta device (hasil dump UI di Sony SOV36 / Android 9)

Ini yang dipakai `TetherScreens.java`. Menghindari tebakan:

| Hal | Nilai di device ini |
|---|---|
| Layar hotspot | `com.sonymobile.wifi/.hotspot.WifiApSettings` |
| Sakelar | `com.sonymobile.wifi:id/switch_bar_switch` (`checkable`, `clickable`) |
| Dialog konfirmasi | **tidak ada** — klik langsung nyala/mati |
| `android.settings.WIFI_TETHERING_SETTINGS` | **tidak resolve** ❌ → wajib lewat component name |
| Layar tethering AOSP | `com.android.settings/.Settings$TetherSettingsActivity` — baris "Portable hotspot" **tanpa switch** (harus diklik untuk masuk) |
| Jebakan | di layar Sony ada `android:id/switch_widget` milik preferensi lain ("Make network name discoverable") → id generik tidak dipakai kalau ada id Sony |

Logika klik-nya aman: sebelum mengklik, `isChecked()` dibandingkan dengan target,
jadi tidak akan salah-toggle kalau hotspot sudah menyala/mati.

---

## Nama WiFi (SSID) — kenapa perlu tombol "Baca"

Tanpa root, app **tidak bisa membaca** konfigurasi hotspot
(`getWifiApConfiguration()` dijaga `NETWORK_SETTINGS`), jadi SSID yang tampil
mula-mula hanya nilai default app (`Mifi-<MODEL>`), **bukan** nama asli.

Solusinya: tombol **"Baca nama WiFi dari HP"** (atau `POST /api/ssid/read`) —
automasi membuka layar hotspot sebentar, memanen teks
`"Network name: <SSID>\nSecurity: …"`, lalu kembali. Nama asli disimpan dan
ditampilkan sebagai `ssid … (dibaca dari sistem)`, sedangkan nilai app tetap
tampil sebagai `ssid app`.

Kalau ada root, nama dibaca langsung dari `getWifiApConfiguration()` (tanpa buka layar).

| Field di `/api/status` | Arti |
|---|---|
| `ssid` | nama terbaik yang diketahui (sistem diutamakan) |
| `ssidSystem` | nama asli hasil bacaan layar Setelan (kosong kalau belum dibaca) |
| `ssidApp` | nilai yang disimpan app |

---

## Fitur

- Nyalakan/matikan hotspot (tombol app, QS tile, atau API) — **tanpa root**.
- Status, SSID, backend yang dipakai, plus log langkah demi langkah.
- **Daftar perangkat terhubung** (IP/MAC/hostname) saat hotspot menyala.
- Konfigurasi SSID / password / band disimpan di app (SSID+password sebenarnya
  diatur di layar "Configure hotspot" milik sistem).
- **HTTP API + halaman web** (port default **8090**), auth PIN (default `admin`).
- Quick Settings tile + autostart saat boot (opsional).

## Build & instal

```bash
./scripts/build-hotspot.sh                 # -> apps/hotspot/out/hotspot.apk
./scripts/test-hotspot.sh                  # install + adb forward + cek API
#   ./scripts/test-hotspot.sh QV7005FB11   # pilih device
```

## HTTP API

Wajib PIN lewat header `X-Auth: <pin>` (atau `?pin=`), kecuali
`/`, `/api/health`, `/api/status`, `/api/clients`.

| Method | Path | Fungsi |
|---|---|---|
| GET | `/` | halaman kontrol web |
| GET | `/api/status` | state, ssid, backend, aksesibilitas, clients |
| GET | `/api/clients` | daftar client |
| GET | `/api/capabilities` | sdk, root, tetherPermission, aksesibilitas, hint |
| GET | `/api/log` | log aktivitas |
| POST | `/api/hotspot/start` | nyalakan (opsional body `{ssid,pass,band}`) |
| POST | `/api/hotspot/stop` | matikan |
| POST | `/api/settings` | buka layar tethering |
| POST | `/api/accessibility/open` | buka Setelan aksesibilitas |
| POST | `/api/config` | simpan `{ssid,pass,band,pin,port,autostart}` |

Contoh nyata yang sudah diuji di Sony SOV36:

```bash
$ADB forward tcp:18090 tcp:8090
curl -s -H 'X-Auth: admin' -X POST localhost:18090/api/hotspot/start
# {"ok":true,...,"enabled":true,...,"backend":"accessibility"}
```

---

## Arsitektur

```
HotspotActivity ---\
HotspotTileService -+--> HotspotEngine --> rantai backend --> HOTSPOT
HotspotService ----/         |                1 privileged
   (foreground + API)        |                2 root (TetherCli via app_process)
                             |                3 accessibility  <-- terverifikasi
        ApiServer            |                4 settings (fallback)
        └ res/raw/control.html
                             |
        HotspotAccessibilityService --> TetherScreens --> klik sakelar di Setelan
```

- `TetherScreens.java` — pengetahuan layar tethering (component, id sakelar, navigasi, dialog).
- `HotspotAccessibilityService.java` — pengklik; batas paket + cek `isChecked()` + auto-back.
- `ApApi.java` — refleksi `setWifiApEnabled/isWifiApEnabled/…` (dipakai jalur privileged & root).
- `TetherCli.java` — entry point `app_process` (jalur root; sudah diuji menghasilkan JSON valid).

---

## Troubleshooting

| Gejala | Penyebab / solusi |
|---|---|
| Tombol cuma membuka Setelan | layanan aksesibilitas belum dicentang → aktifkan lewat app. |
| Log: `✗ automasi: timeout` | layout ROM berbeda dari yang terverifikasi; kirim `log` + dump UI. |
| `rootError: Permission denied` | normal tanpa root — bukan masalah. |
| Log: `✗ privileged: NoSuchMethodException` | normal; `setWifiApEnabled` memang dihapus di Android 10+. |
| Status "perkiraan" padahal hotspot nyala | tanpa root state tidak bisa dibaca ulang; app memakai state yang terakhir di-set. |
| Client tidak muncul | daftar dibaca dari `/proc/net/arp` + lease dnsmasq; hanya ditampilkan saat hotspot menyala. |

### Android 10+
`setWifiApEnabled` dihapus dan `startTethering` butuh `TETHER_PRIVILEGED`. Backend
privileged akan gagal (`NoSuchMethodException`) dan otomatis jatuh ke
**accessibility** — selama layar hotspotnya masih bisa dibuka & sakelarnya punya
id `android:id/switch_widget`/`com.sonymobile.wifi:id/switch_bar_switch`.
