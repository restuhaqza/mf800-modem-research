#!/bin/zsh
# Build hotspot.apk (Java saja, tanpa library native, tanpa Gradle).
#
# App "WiFi Modem": kontrol hotspot + daftar client, dengan HTTP API & halaman web.
#   Pakai: ./scripts/build-hotspot.sh
set -euo pipefail

SDK="${ANDROID_SDK:-$HOME/Library/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-28/android.jar"

[ -f "$BT/aapt" ] || { echo "build-tools tidak ditemukan: $BT" >&2; exit 1; }
[ -f "$JAR" ]     || { echo "android.jar tidak ditemukan: $JAR" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P="$ROOT/apps/hotspot"

# keystore debug: pakai milik mifi-helper kalau belum ada
if [ ! -f "$P/debug.keystore" ]; then
  cp "$ROOT/apps/mifi-helper/debug.keystore" "$P/debug.keystore"
fi

rm -rf "$P/obj" "$P/gen"
mkdir -p "$P/obj" "$P/gen" "$P/out"

# 1) resource + R.java
"$BT/aapt" package -f -m -M "$P/AndroidManifest.xml" -S "$P/res" -I "$JAR" -J "$P/gen"

# 2) kompilasi Java (org.json & TileService sudah ada di android.jar)
javac --release 8 -nowarn -cp "$JAR" -d "$P/obj" \
    "$P/gen/com/mifi/hotspot/R.java" "$P/src/com/mifi/hotspot/"*.java

# 3) dex
"$BT/d8" --release --min-api 19 --lib "$JAR" --output "$P/out" "$P/obj/com/mifi/hotspot/"*.class

# 4) package, align, sign
rm -f "$P/out/base.apk" "$P/out/aligned.apk" "$P/out/hotspot.apk"
"$BT/aapt" package -f -M "$P/AndroidManifest.xml" -S "$P/res" -I "$JAR" -F "$P/out/base.apk"
(
  cd "$P/out"
  "$BT/aapt" add base.apk classes.dex
  "$BT/zipalign" -f 4 base.apk aligned.apk
)
"$BT/apksigner" sign --ks "$P/debug.keystore" --ks-pass pass:android --min-sdk-version 19 \
    --out "$P/out/hotspot.apk" "$P/out/aligned.apk"

echo "built: $P/out/hotspot.apk"
"$BT/aapt" list "$P/out/hotspot.apk" | grep -E 'classes\.dex|res/raw/control' || true
