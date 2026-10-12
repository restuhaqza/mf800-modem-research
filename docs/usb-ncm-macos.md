# Internet via USB ke macOS (MF800)

## Gejala
Modem dihubungkan ke Mac lewat USB, ADB jalan, admin web bisa via `adb forward`,
**tapi Mac tidak dapat interface jaringan / tidak ada internet dari USB**.

## Akar masalah
Firmware meng-expose jaringan USB sebagai **RNDIS**:

```
$ adb shell getprop sys.usb.config
rndis,serial_smd,diag,adb
$ adb shell ifconfig rndis0
rndis0 ... master br0
```

`rndis0` di-bridge OEM ke `br0` (bersama `wlan0`), `br0` = `192.168.100.1/24`,
`dnsmasq` melayani DHCP, dan NAT masquerade ke `rmnet0` sudah ada. Jadi sisi modem
sudah benar.

Masalahnya di macOS: **macOS tidak punya driver RNDIS** (Apple hanya menyediakan
`AppleUSBECM` / `AppleUSBDeviceNCM`). Windows punya RNDIS, macOS tidak. Karena itu
tidak ada interface jaringan yang dibuat untuk modem.

```
$ kmutil showloaded | grep -Ei 'rndis|ecm|ncm'
com.apple.driver.AppleUSBDeviceNCM      (ada)
com.apple.driver.usb.cdc.ecm            (ada)
# tidak ada driver rndis
```

## Solusi: pakai CDC-NCM (didukung native macOS)
Kernel modem mendukung gadget `ncm` dan `firmware init.qcom.usb.rc` punya komposisi
`ncm,adb` (idVendor `05C6`, idProduct `908C`). Setelah gadget diganti ke NCM:

- macOS membuat interface ethernet (mis. `en11`) dan mengambil DHCP `192.168.100.x`
  dari `dnsmasq` modem, lalu NAT ke internet.
- `ncm0` harus di-bridge ke `br0` (OEM hanya bridge `rndis0`, lihat
  `LedController.handle_usb()` di source decompile `MifiService.apk` — disimpan lokal
  di luar repo).

Terbukti jalan: egress IP publik = IP seluler modem, DNS = `192.168.100.1`.

## Kenapa tidak dibikin permanen di device?
1. Android **UsbDeviceManager** hanya mengenal `mtp/ptp/rndis/adb/...`; fungsi `ncm`
   di `persist.sys.usb.config` akan diabaikan/ditimpa saat boot, dan tethering OEM
   memaksa `rndis`.
2. Berpindah komposisi USB saat `adbd` hidup memicu bug functionfs
   (`functionfs_closed_callback: adb_closed_callback: config->dev is NULL`) yang
   membuat ADB di Mac putus.

Karena itu komposisi **tetap RNDIS sebagai default** (ADB stabil, perilaku OEM utuh),
dan switch ke NCM dilakukan **saat runtime dari Mac lewat ADB**. Reboot/re-plug
mengembalikan ke RNDIS; agent launchd akan menyalakan NCM lagi otomatis.

## Cara pakai
```bash
# sekali (manual)
./scripts/usb-ncm/enable-usb-ncm.sh

# otomatis: agent launchd memeriksa tiap 30 dtk; kalau modem LAN (192.168.100.1)
# belum reachable, ia menjalankan enabler (no-op kalau sudah online).
./scripts/usb-ncm/install-agent.sh      # pasang
./scripts/usb-ncm/uninstall-agent.sh    # hapus
```
Log agent: `/tmp/mf800-usb-ncm.log`.

## Catatan
- Saat NCM aktif, Mac langsung bisa akses `http://192.168.100.1` (web) dan
  `:8080` (mifi-helper) tanpa `adb forward`.
- `adb forward tcp:3020 tcp:80` hanya perlu saat mode RNDIS (stock), karena Mac
  belum punya interface LAN modem.
- Komposisi NCM juga dikenali Windows 10+ secara native.
- Jangan set `persist.sys.usb.config=ncm,adb`: terbukti bikin ADB hilang saat boot.
