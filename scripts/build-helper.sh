#!/bin/zsh
# Build mifi-helper.apk (Java + native libmifipty.so) without Gradle.
#
# The native helper allocates a real PTY for the interactive terminal; Android's
# bionic libc has no openpty, so mifi_pty.c drives /dev/ptmx itself. NDK r27 only
# ships API 21 headers; the library uses ancient libc/kernel interfaces, so it
# loads fine on this API 19 device.
#
# Pemakaian: ./scripts/build-helper.sh
set -euo pipefail

SDK="${ANDROID_SDK:-$HOME/Library/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-28/android.jar"
NDK="${ANDROID_NDK:-$SDK/ndk/27.1.12297006}"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
CC="$TC/armv7a-linux-androideabi21-clang"
STRIP="$TC/llvm-strip"

[ -x "$CC" ] || { echo "clang NDK tidak ditemukan: $CC" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P="$ROOT/apps/mifi-helper"

rm -rf "$P/obj" "$P/gen"
mkdir -p "$P/obj" "$P/gen" "$P/out/lib/armeabi-v7a"

# 1) native PTY helper
"$CC" -shared -fPIC -O2 -Wall -o "$P/out/lib/armeabi-v7a/libmifipty.so" \
    "$P/src/com/mifi/helper/mifi_pty.c"
"$STRIP" --strip-unneeded "$P/out/lib/armeabi-v7a/libmifipty.so"

# 2) resources + java
"$BT/aapt" package -f -m -M "$P/AndroidManifest.xml" -S "$P/res" -I "$JAR" -J "$P/gen"
javac --release 8 -nowarn -cp "$JAR" -d "$P/obj" \
    "$P/gen/com/mifi/helper/R.java" "$P/src/com/mifi/helper/"*.java

# 3) dex
"$BT/d8" --release --min-api 19 --lib "$JAR" --output "$P/out" "$P/obj/com/mifi/helper/"*.class

# 4) package (classes.dex + native lib), align, sign
rm -f "$P/out/base.apk" "$P/out/aligned.apk" "$P/out/mifi-helper.apk"
"$BT/aapt" package -f -M "$P/AndroidManifest.xml" -S "$P/res" -I "$JAR" -F "$P/out/base.apk"
(
  cd "$P/out"
  "$BT/aapt" add base.apk classes.dex
  "$BT/aapt" add base.apk lib/armeabi-v7a/libmifipty.so
  "$BT/zipalign" -f 4 base.apk aligned.apk
)
"$BT/apksigner" sign --ks "$P/debug.keystore" --ks-pass pass:android --min-sdk-version 19 \
    --out "$P/out/mifi-helper.apk" "$P/out/aligned.apk"

echo "built: $P/out/mifi-helper.apk"
"$BT/aapt" list "$P/out/mifi-helper.apk" | grep -E 'classes\.dex|libmifipty' || true
