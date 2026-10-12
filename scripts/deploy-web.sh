#!/bin/zsh
# Deploy web UI ke modem MF800 + lock down agar persisten setelah reboot.
#
# Root cause: JServer.extractAssets() (MifiService.apk) menimpa SEMUA file
# di jetty2 dengan versi dari APK assets setiap kali Jetty start (tiap boot).
# Solusi: file dibuat root:root 755 — penulisan ulang oleh uid system gagal
# silent (FileNotFoundException ter-catch di JServer.java:117), Jetty tetap
# bisa membaca (world-readable).
#
# Pemakaian: ./scripts/deploy-web.sh
set -euo pipefail

ADB=~/Library/Android/Sdk/platform-tools/adb
J=/data/data/com.mifiservice.hello/files/jetty2
WEB="$(cd "$(dirname "$0")/.." && pwd)/web"

[ -x "$ADB" ] || { echo "adb tidak ditemukan: $ADB" >&2; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "Device tidak terhubung" >&2; exit 1; }

cd "$WEB"

# Top-level files (kecuali dir staging web/jetty2 — jangan ikut di-push).
# PDF manual OEM tidak disertakan di repo (lihat README) — push hanya bila ada.
files=(index.html main.html chat.html D_HCP.html deviceInformation.html \
  deviceOperation.html DNS.html help.html MacFilter.html modifyPwd.html \
  ms.html netsel.html reboot-bootloader.html sim.html sms.html static.html \
  status.html terminal.html upgrade.html usbdebug.html ussd.html \
  wifiSecurity.html wifiSetting.html wwanConfig.html)
[ -f USER_Manual_en.pdf ] && files+=(USER_Manual_en.pdf)
[ -f USER_Manual_zh.pdf ] && files+=(USER_Manual_zh.pdf)
$ADB push "${files[@]}" "$J/" >/dev/null
for d in css js img json; do
  [ -d "$d" ] && $ADB push "$d" "$J/" >/dev/null
done

# chown root:root rekursif (toolbox chown tidak punya -R) + chmod 755
$ADB shell "cd $J && for d in . */ */*/; do chown root:root \"\$d\" 2>/dev/null; for f in \"\$d\"*; do chown root:root \"\$f\" 2>/dev/null; done; done; chmod -R 755 ."

echo "Deploy + lockdown OK. Verifikasi:"
$ADB shell "ls -l $J/index.html $J/main.html $J/css/restu-v6.css"
echo "Catatan: factory reset (funcNo 1014) tetap menghapus jetty2 — jalankan script ini lagi."
