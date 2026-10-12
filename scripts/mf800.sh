#!/usr/bin/env bash
# ============================================================================
# scripts/mf800.sh — panel interaktif "Ngoprek Modem" (JogjaDevs · Lo-Tech Meetup)
#
# Satu pintu untuk demo live, mengikuti alur slide: Dump → Modify → Deploy.
#
#   1. Deteksi modem   device ADB, model/OS/root, RAN, sinyal, partisi
#   2. Switch mode     RAN: Auto / 3G-only / 4G-only lewat `modem_at AT^SYSCONFIG`
#   3. Dump firmware   snapshot tiap partisi (/dev/block/by-name) → ./dumps/
#   4. Deploy          push web UI + kunci ownership (persist lintas reboot)
#
# Bonus: buka web admin via adb forward, internet USB (NCM), bangun+instal helper.
#
# Pemakaian:
#   ./scripts/mf800.sh                     # menu interaktif
#   ./scripts/mf800.sh detect              # 1x deteksi modem
#   ./scripts/mf800.sh mode 4g             # auto | 3g | 4g | <attype>
#   ./scripts/mf800.sh dump [--force] [--only modem,modemst1]
#   ./scripts/mf800.sh deploy
#   ./scripts/mf800.sh web                 # adb forward + URL
#   ./scripts/mf800.sh --serial <SN> ...
# ============================================================================

# Catatan: sengaja TANPA `set -e` — ini menu interaktif, error tak boleh mematikan sesi.
set -o pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL=""
DEV_LABEL=""

# ---------- warna ----------
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; RST=$'\033[0m'
  RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'
  MAG=$'\033[35m'; CYN=$'\033[36m'
else
  BOLD=; DIM=; RST=; RED=; GRN=; YEL=; MAG=; CYN=
fi

say()  { printf '%s\n' "$*"; }
info() { printf '%s\n' "${CYN}▸${RST} $*"; }
ok()   { printf '%s\n' "${GRN}✓${RST} $*"; }
warn() { printf '%s\n' "${YEL}!${RST} $*"; }
err()  { printf '%s\n' "${RED}✗${RST} $*" >&2; }
hr()   { printf '%s\n' "${DIM}────────────────────────────────────────────────────────────${RST}"; }
kv()   { printf '  %-14s %s\n' "$1" "$2"; }

human() { awk -v b="${1:-0}" 'BEGIN{u[0]="B";u[1]="KB";u[2]="MB";u[3]="GB";i=0;while(b>=1024&&i<3){b/=1024;i++}printf "%.1f %s",b,u[i]}'; }

# ---------- adb ----------
resolve_adb() {
  if [ -n "${ADB:-}" ]; then
    if [ -x "$ADB" ] || command -v "$ADB" >/dev/null 2>&1; then return 0; fi
  fi
  if [ -x "$HOME/Library/Android/Sdk/platform-tools/adb" ]; then
    ADB="$HOME/Library/Android/Sdk/platform-tools/adb"
  elif command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
  else
    err "adb tidak ditemukan. Set ADB=/path/ke/adb atau pasang platform-tools."
    return 1
  fi
}

# `cmd` = adb [+ -s SERIAL]; dipakai untuk semua perintah device.
# stdin diarahkan ke /dev/null supaya adb tak "memakan" input skrip
# (mis. jawaban prompt dump) — adb shell memang meneruskan stdin ke device.
cmd() {
  if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@" </dev/null; else "$ADB" "$@" </dev/null; fi
}

list_devices() {
  resolve_adb || return 1
  "$ADB" start-server >/dev/null 2>&1 </dev/null
  "$ADB" devices -l </dev/null
}

pick_serial() {
  resolve_adb || return 1
  "$ADB" start-server >/dev/null 2>&1 </dev/null
  local online count i s choice
  online=$("$ADB" devices </dev/null | awk 'NR>1 && $2=="device" {print $1}')
  count=$(printf '%s\n' "$online" | grep -c .)
  if [ "$count" -eq 0 ]; then
    err "Tidak ada device ADB berstatus 'device'."
    "$ADB" devices -l >&2 </dev/null
    return 1
  fi
  if [ "$count" -eq 1 ]; then
    SERIAL="$online"
  else
    say "Beberapa device terdeteksi:" >&2
    i=1
    for s in $online; do say "  $i) $s" >&2; i=$((i+1)); done
    printf 'pilih nomor> ' >&2
    read -r choice
    i=1
    for s in $online; do
      if [ "$i" = "$choice" ]; then SERIAL="$s"; break; fi
      i=$((i+1))
    done
    [ -n "$SERIAL" ] || { err "pilihan tidak valid"; return 1; }
  fi
  return 0
}

ensure_device() {
  if [ -z "$SERIAL" ]; then
    pick_serial || return 1
  else
    "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 </dev/null || { err "device $SERIAL tidak siap"; SERIAL=""; pick_serial || return 1; }
  fi
  DEV_LABEL="$(cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  return 0
}

# Cek cepat (tanpa memicu error) apakah device masih hidup di USB.
device_alive() {
  [ -n "$SERIAL" ] || return 1
  "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 </dev/null
}

prop() { cmd shell getprop "$1" 2>/dev/null | tr -d '\r'; }
sh1()  { cmd shell "$1" 2>/dev/null | tr -d '\r'; }

# by-name: Qualcomm biasanya di /dev/block/bootdevice/by-name, bukan /dev/block/by-name.
BYNAME=""
resolve_byname() {
  [ -n "$BYNAME" ] && return 0
  local p
  for p in /dev/block/bootdevice/by-name /dev/block/by-name; do
    if [ "$(sh1 "[ -d $p ] && echo yes || echo no")" = "yes" ]; then BYNAME="$p"; return 0; fi
  done
  p=$(sh1 'ls -d /dev/block/platform/*/*/by-name 2>/dev/null' | head -n1)
  [ -n "$p" ] && { BYNAME="$p"; return 0; }
  return 1
}

# ---------- RAN (mode jaringan) ----------
# Mapping diambil verbatim dari WanDataController.setPreferedNetworkType():
#   mode 0 Auto  -> attype 12 (11 utk China Telecom)
#   mode 1 3G    -> attype 14
#   mode 2 4G    -> attype 11
ran_label() {
  case "$1" in
    11) echo "4G / LTE only";;
    12) echo "Auto (GSM/WCDMA/LTE)";;
    14) echo "3G / WCDMA only";;
    2)  echo "3G / WCDMA only (legacy)";;
    "") echo "tidak terbaca";;
    *)  echo "custom ($1)";;
  esac
}

ran_current_code() {
  sh1 'modem_at AT^SYSCONFIG?' | sed -n 's/.*SYSCONFIG: *\([0-9][0-9]*\).*/\1/p' | head -n1
}

set_ran() {
  # $1 = attype (11/12/14/2/...), $2 = android preferred_network_mode (opsional)
  local attype="$1" pnm="${2:-}" out
  if [ "$(sh1 '[ -x /system/bin/modem_at ] && echo yes || echo no')" != "yes" ]; then
    err "modem_at tidak ada di /system/bin — tak bisa kirim AT."
    return 1
  fi
  info "modem_at AT^SYSCONFIG=${attype},0,1,2"
  out=$(sh1 "modem_at AT^SYSCONFIG=${attype},0,1,2")
  [ -n "$out" ] && say "  ${DIM}${out}${RST}"
  if [ -n "$pnm" ]; then
    sh1 "settings put global preferred_network_mode $pnm" >/dev/null
    info "settings put global preferred_network_mode $pnm"
  fi
  sleep 1
  local now; now="$(ran_current_code)"
  if [ "$now" = "$attype" ]; then
    ok "mode sekarang: $(ran_label "$now") (^SYSCONFIG: $now)"
  else
    warn "diminta $attype, terbaca: ${now:-?} — modem mungkin butuh beberapa detik."
  fi
}

set_ran_cli() {
  local m="${1:-}"
  case "$m" in
    ""|auto|0)  set_ran 12 12;;
    3g|1)       set_ran 14 2;;
    4g|lte|2)   set_ran 11 11;;
    [0-9]*)     set_ran "$m" "";;
    *)          err "mode tidak dikenal: $m (pakai auto|3g|4g|<attype>)"; return 2;;
  esac
}

switch_mode_menu() {
  ensure_device || return 1
  local cur; cur="$(ran_current_code)"
  hr
  say "${BOLD}Switch mode jaringan (RAN)${RST}"
  kv "sekarang" "$(ran_label "$cur")  ^SYSCONFIG=${cur:-?}"
  hr
  say "  1) Auto        ${DIM}AT^SYSCONFIG=12,0,1,2${RST}"
  say "  2) 3G only     ${DIM}AT^SYSCONFIG=14,0,1,2${RST}"
  say "  3) 4G only     ${DIM}AT^SYSCONFIG=11,0,1,2${RST}"
  say "  4) custom      ${DIM}isi nilai attype sendiri${RST}"
  say "  b) kembali"
  printf 'pilih> '
  local c
  read -r c
  case "$c" in
    1) set_ran 12 12;;
    2) set_ran 14 2;;
    3) set_ran 11 11;;
    4) printf 'attype> '; read -r c; [ -n "$c" ] && set_ran "$c" "";;
    b|B|"") return 0;;
    *) warn "pilihan tidak dikenal";;
  esac
}

# ---------- deteksi modem ----------
detect_modem() {
  ensure_device || return 1
  resolve_adb || return 1
  resolve_byname >/dev/null 2>&1 || true
  hr
  say "${BOLD}${MAG}MF800 · deteksi modem${RST}"
  hr

  local slot model brand dev ver sdk fw build serial kernel id_out root
  slot=$(prop ro.boot.slot_suffix)
  model=$(prop ro.product.model)
  brand=$(prop ro.product.brand)
  dev=$(prop ro.product.device)
  ver=$(prop ro.build.version.release)
  sdk=$(prop ro.build.version.sdk)
  fw=$(prop ro.build.display.id)
  build=$(prop ro.build.id)
  serial=$(prop ro.serialno)
  # toolbox Android 4.4 tak punya `uname`; baca /proc/version + abi getprop.
  local kver abi
  kver=$(sh1 'cat /proc/version' | sed -n 's/^Linux version \([^ ]*\).*/\1/p')
  abi=$(prop ro.product.cpu.abi)
  kernel="${kver:-?} ${DIM}${abi}${RST}"
  id_out=$(sh1 id)
  root="no"
  case "$id_out" in *uid=0*) root="${GRN}ya${RST}";; *) root="${YEL}tidak${RST}";; esac

  say "${BOLD}device${RST}"
  kv "adb serial" "${SERIAL:-—}"
  kv "model" "${model:-—} ${DIM}(${brand:-?} · ${dev:-?})${RST}"
  kv "android" "${ver:-?} ${DIM}(API ${sdk:-?})${RST}"
  kv "firmware" "${fw:-?}"
  kv "build" "${build:-?}"
  kv "serial" "${serial:-—}"
  kv "kernel" "${kernel:-?}"
  kv "root(/adb)" "$root"
  [ -n "$slot" ] && kv "slot" "$slot"

  say ""
  say "${BOLD}penyimpanan${RST}"
  kv "/data" "$(df_line /data)"
  kv "/system" "$(df_line /system)"

  say ""
  say "${BOLD}radio${RST}"
  local csq sysconf cops usb opname act
  csq=$(sh1 'modem_at AT+CSQ' | grep -o '+CSQ:.*' | head -n1)
  sysconf=$(ran_current_code)
  cops=$(sh1 'modem_at AT+COPS?' | grep -o '+COPS:.*' | head -n1)
  opname=$(printf '%s' "$cops" | sed -n 's/.*"\(.*\)".*/\1/p')
  act=$(printf '%s' "$cops" | sed -n 's/.*,\([0-9][0-9]*\)$/\1/p')
  usb=$(prop sys.usb.state)
  [ -z "$usb" ] && usb=$(prop sys.usb.config)
  local cap; cap=$(sh1 'cat /sys/class/power_supply/battery/capacity 2>/dev/null')
  kv "sinyal" "${csq:-tidak terbaca}"
  kv "RAN" "$(ran_label "$sysconf")  ${DIM}^SYSCONFIG=${sysconf:-?}${RST}"
  kv "operator" "${opname:-—} ${DIM}· $(act_label "$act")${RST}"
  kv "usb" "${usb:-—}"
  if [ -n "$cap" ]; then
    if [ "$cap" -lt 20 ] 2>/dev/null; then kv "baterai" "${RED}${cap}%${RST} ${DIM}(rendah — isi daya sebelum dump besar)${RST}"
    else kv "baterai" "${cap}%"; fi
  fi
  kv "partisi" "$(partition_count) entri di ${BYNAME:-?}"
  hr
}

df_line() {
  sh1 "df $1" | awk 'NR>1 && NF>=4 {printf "%s total · %s used · %s free", $2, $3, $4; exit}'
}

act_label() {
  case "$1" in
    0) echo "GSM";;
    2) echo "UTRAN (3G)";;
    7) echo "E-UTRAN (LTE)";;
    "") echo "";;
    *) echo "AcT $1";;
  esac
}

partition_count() {
  parse_partitions | grep -c .
}

# ---------- partisi ----------
# Output: "<name> <devnode>" per baris, diambil dari symlink by-name.
parse_partitions() {
  resolve_byname || return 1
  sh1 "ls -l $BYNAME" | awk '
    { for (i=1;i<=NF;i++) if ($i=="->") { print $(i-1), $(i+1) } }
  '
}

# Peta ukuran blok (512-byte sectors) diambil sekali dari /sys/class/block.
block_sizes() {
  sh1 'for d in /sys/class/block/*; do n=${d##*/}; [ -r "$d/size" ] && echo "$n $(cat "$d/size" 2>/dev/null)"; done'
}

size_of_dev() {
  awk -v d="$2" '$1==d{print $2; exit}' "$1"
}

dump_firmware() {
  ensure_device || return 1
  local force="${FORCE:-0}" only="${ONLY:-}" all="${ALL:-0}"
  local exclude="userdata,cache"
  local out="$ROOT/dumps"
  mkdir -p "$out"

  if ! resolve_byname; then
    err "tabel partisi by-name tidak ditemukan (device tidak root?)"
    return 1
  fi
  local sizes_file; sizes_file="$(mktemp -t mf800sizes)"
  info "memetakan partisi $BYNAME ..."
  block_sizes > "$sizes_file"

  local names=() devs=() byts=() total=0
  local name devnode sec bytes
  while read -r name devnode; do
    [ -n "$name" ] || continue
    if [ -n "$only" ]; then
      case ",$only," in *",$name,"*) : ;; *) continue ;; esac
    elif [ "$all" != "1" ]; then
      # default: firmware saja — lewati partisi data dinamis yang besar.
      case ",$exclude," in *",$name,"*) continue ;; esac
    fi
    sec=$(size_of_dev "$sizes_file" "$(basename "$devnode")")
    [ -n "$sec" ] || sec=0
    bytes=$(( sec * 512 ))
    names+=("$name"); devs+=("$devnode"); byts+=("$bytes")
    total=$(( total + bytes ))
  done < <(parse_partitions)

  if [ "${#names[@]}" -eq 0 ]; then
    err "tak ada partisi terbaca (device tidak root / by-name kosong?)"
    rm -f "$sizes_file"; return 1
  fi

  rm -f "$sizes_file"

  # Preflight daya: dump besar bisa mematikan modem dengan baterai lemah.
  local cap; cap=$(sh1 'cat /sys/class/power_supply/battery/capacity 2>/dev/null')
  if [ -n "$cap" ] && [ "$cap" -lt 25 ] 2>/dev/null; then
    warn "baterai modem ${RED}${cap}%${RST} — isi daya dulu (>30%) atau dump bertahap."
    say "  ${DIM}Dump besar + USB bisa bikin device mati di tengah jalan.${RST}"
  fi

  hr
  say "${BOLD}${MAG}Dump firmware — snapshot partisi${RST}"
  hr
  printf '  %-14s %-18s %10s  %s\n' "PARTISI" "DEVNODE" "UKURAN" "STATUS"
  local i=0
  while [ "$i" -lt "${#names[@]}" ]; do
    local d="$out/${names[$i]}.img" st
    if [ -s "$d" ] && [ "$force" != "1" ]; then st="${GRN}ada (dilewati)${RST}"; else st="${YEL}akan di-dump${RST}"; fi
    printf '  %-14s %-18s %10s  %s\n' "${names[$i]}" "$(basename "${devs[$i]}")" "$(human "${byts[$i]}")" "$st"
    i=$((i+1))
  done
  hr
  kv "tujuan" "$out"
  kv "total" "$(human "$total")"
  if [ "$all" != "1" ] && [ -z "$only" ]; then
    say "  ${DIM}userdata & cache dilewati (data dinamis) — pakai --all untuk dump penuh.${RST}"
  fi
  say ""
  say "  ${DIM}dump = baca read-only dari partisi; tidak mengubah apa pun di modem.${RST}"
  printf 'lanjut dump? [y/N] '
  local a; read -r a
  case "$a" in y|Y|yes|YES) : ;; *) warn "dibatalkan"; return 0 ;; esac

  local manifest="$out/MANIFEST.txt"
  if [ ! -f "$manifest" ]; then
    {
      echo "# MF800 firmware dump"
      echo "# serial : ${SERIAL:-?}"
      echo "# model  : $(prop ro.product.model)"
      echo "# tanggal: $(date '+%Y-%m-%d %H:%M:%S')"
      echo "# partisi devnode size_bytes sha256 file"
    } > "$manifest"
  else
    echo "# --- lanjutan $(date '+%Y-%m-%d %H:%M:%S') ---" >> "$manifest"
  fi

  i=0
  while [ "$i" -lt "${#names[@]}" ]; do
    name="${names[$i]}"; devnode="${devs[$i]}"; bytes="${byts[$i]}"
    local dest="$out/${name}.img" tmp="$out/.${name}.img.part"
    if [ -s "$dest" ] && [ "$force" != "1" ]; then
      printf '  %s %-14s %s\n' "${DIM}skip${RST}" "$name" "$(human "$(wc -c < "$dest" | tr -d ' ')")"
      i=$((i+1)); continue
    fi
    if ! device_alive; then
      say ""
      err "device ${SERIAL:-?} hilang dari USB di tengah dump — dihentikan."
      say "  ${DIM}Yang sudah ada aman; isi daya lalu ulangi: ./scripts/mf800.sh dump${RST}"
      return 1
    fi
    printf '  %s %-14s %s ...' "${YEL}dump${RST}" "$name" "$(human "$bytes")"
    rm -f "$tmp"
    local pullerr; pullerr="$(mktemp -t mf800pull)"
    # 1) adb pull langsung dari block device: streaming, tanpa ruang /data,
    #    tanpa translasi PTY (adbd Android 4.4 tak mendukung `exec-out`).
    "$ADB" ${SERIAL:+-s "$SERIAL"} pull "$BYNAME/$name" "$tmp" >"$pullerr" 2>&1 </dev/null
    # 2) fallback: exec-out streaming (adbd modern).
    if [ ! -s "$tmp" ]; then
      cmd exec-out "dd if=$BYNAME/$name bs=1M 2>/dev/null" > "$tmp" 2>/dev/null
    fi
    # 3) fallback terakhir: dd ke /data lalu pull.
    if [ ! -s "$tmp" ]; then
      local devtmp="/data/local/tmp/mf800-dump-$name.img"
      cmd shell "dd if=$BYNAME/$name of=$devtmp bs=1M" >/dev/null 2>&1
      "$ADB" ${SERIAL:+-s "$SERIAL"} pull "$devtmp" "$tmp" >/dev/null 2>&1 </dev/null
      cmd shell "rm -f $devtmp" >/dev/null 2>&1
    fi
    if [ ! -s "$tmp" ]; then
      say " ${RED}gagal${RST}"
      [ -s "$pullerr" ] && sed 's/^/      /' "$pullerr" | head -n 3
      rm -f "$tmp" "$pullerr"
      if ! device_alive; then
        say ""
        err "device ${SERIAL:-?} hilang dari USB (kabel / daya / reboot) — dihentikan."
        say "  ${DIM}Partisi yang sudah ada aman; isi daya lalu ulangi: ./scripts/mf800.sh dump${RST}"
        return 1
      fi
      i=$((i+1)); continue
    fi
    rm -f "$pullerr"
    mv -f "$tmp" "$dest"
    local got; got=$(wc -c < "$dest" | tr -d ' ')
    local sum; sum=$( (shasum -a 256 "$dest" 2>/dev/null || sha256sum "$dest" 2>/dev/null) | awk '{print $1}' )
    if [ "$bytes" -gt 0 ] && [ "$got" != "$bytes" ]; then
      printf ' %s %s %s\n' "${YEL}ok*${RST}" "$(human "$got")" "${DIM}(size beda: ${bytes}B)${RST}"
    else
      printf ' %s %s\n' "${GRN}ok${RST}" "$(human "$got")"
    fi
    echo "$name $devnode $got $sum $(basename "$dest")" >> "$manifest"
    i=$((i+1))
  done

  hr
  ok "dump selesai → $out"
  say "  manifest: $manifest"
  say "  ${DIM}Simpan di tempat aman: modemst1/2 berisi IMEI & kalibrasi radio.${RST}"
}

# ---------- deploy ----------
do_deploy() {
  ensure_device || return 1
  hr
  say "${BOLD}${MAG}Deploy web UI → jetty2 + lockdown${RST}"
  hr
  if [ ! -x "$ROOT/scripts/deploy-web.sh" ]; then
    err "scripts/deploy-web.sh tidak ada / tidak executable"
    return 1
  fi
  "$ROOT/scripts/deploy-web.sh"
}

# ---------- buka web admin ----------
do_web() {
  ensure_device || return 1
  info "adb forward tcp:18081 -> tcp:80   (web admin)"
  cmd forward tcp:18081 tcp:80 >/dev/null
  info "adb forward tcp:18080 -> tcp:8080 (mifi-helper)"
  cmd forward tcp:18080 tcp:8080 >/dev/null
  hr
  kv "web admin" "http://localhost:18081  ${DIM}(admin/admin)${RST}"
  kv "helper" "http://localhost:18080/api/status"
  kv "terminal" "http://localhost:18081/terminal.html"
  local st; st=$(curl -s --max-time 4 http://localhost:18080/api/status 2>/dev/null)
  [ -n "$st" ] && say "  ${DIM}${st}${RST}"
  hr
  if command -v open >/dev/null 2>&1; then
    open "http://localhost:18081" >/dev/null 2>&1 && ok "browser dibuka"
  fi
}

# ---------- debug mode (aktifkan ADB/diag lewat funcNo 2001) ----------
# OEM: AjaxSevlet funcNo 2001 -> WanDataController.setDebugMode()
#      = `AT+USB=1,268968726`, lalu DeviceController.rebootDevice().
# Endpoint /ajax tidak memerlukan cookie/session, jadi bisa dipicu 1 POST saja.
debug_mode() {
  local yes="${1:-0}"
  local host="${MF800_HOST:-192.168.100.1}" base
  resolve_adb >/dev/null 2>&1 || true

  if command -v curl >/dev/null 2>&1; then :; else
    err "curl tidak ditemukan — diperlukan untuk memicu funcNo 2001."; return 1
  fi

  # Bila device sudah terlihat di ADB, pakai forward; kalau belum (mode normal),
  # host harus bisa menjangkau modem LAN (WiFi modem, atau RNDIS di Windows).
  if [ -n "${ADB:-}" ] && "$ADB" get-state >/dev/null 2>&1 </dev/null; then
    "$ADB" forward tcp:18081 tcp:80 >/dev/null 2>&1 </dev/null
    base="http://localhost:18081"
  else
    base="http://$host"
  fi

  hr
  say "${BOLD}${MAG}Debug mode — aktifkan ADB + diag${RST}"
  hr
  say "  aksi   : POST /ajax  {funcNo:2001}"
  say "  isi    : ${DIM}AT+USB=1,268968726${RST} (setDebugMode) + reboot"
  say "  target : $base/ajax"
  if [ "$base" = "http://localhost:18081" ]; then
    say "  jalur  : ADB forward (device sudah terlihat)"
    local st; st=$(curl -s --max-time 5 -X POST "$base/ajax" -d '{"funcNo":1021}' 2>/dev/null)
    case "$st" in
      *'"mode":"1"'*) say "  status : ${YEL}ADB sudah aktif (mode debug)${RST}";;
      *'"mode":"0"'*) say "  status : ADB nonaktif";;
    esac
  else
    say "  jalur  : LAN modem langsung ${DIM}(pastikan host ada di WiFi modem)${RST}"
  fi
  say ""
  say "  ${YEL}⚠ Modem akan REBOOT.${RST} ADB & port diag aktif setelah boot berikutnya."
  if [ "$yes" != "1" ]; then
    printf 'lanjut? [y/N] '
    local a; read -r a
    case "$a" in y|Y|yes|YES) : ;; *) warn "dibatalkan"; return 0 ;; esac
  fi

  local out
  out=$(curl -s --max-time 8 -X POST "$base/ajax" -d '{"funcNo":2001}' 2>/dev/null)
  case "$out" in
    *'"flag":"1"'*)
      ok "perintah debug terkirim — modem reboot sekarang."
      say "  ${DIM}tunggu ±30–60 dtk, lalu:${RST}"
      say "  ${DIM}  \$ADB wait-for-device && adb devices${RST}"
      say "  ${DIM}  ./scripts/mf800.sh detect${RST}"
      ;;
    "")
      err "tidak ada respons dari $base — host tidak menjangkau modem?"
      say "  ${DIM}Mode normal RNDIS tak terlihat di macOS; sambungkan Mac ke WiFi modem,${RST}"
      say "  ${DIM}atau jalankan dari HP/Windows. Ulangi: MF800_HOST=<ip> $0 debug${RST}"
      ;;
    *) err "respons tak terduga: $out";;
  esac
}

# ---------- internet USB (NCM) ----------
do_ncm() {
  local s="$ROOT/scripts/usb-ncm/enable-usb-ncm.sh"
  [ -x "$s" ] || { err "tidak ada: $s"; return 1; }
  ADB="$ADB" "$s"
}

# ---------- helper APK ----------
do_helper() {
  ensure_device || return 1
  local build="$ROOT/scripts/build-helper.sh"
  local apk="$ROOT/apps/mifi-helper/out/mifi-helper.apk"
  if [ -x "$build" ]; then
    info "build mifi-helper ..."
    "$build" || return 1
  fi
  [ -f "$apk" ] || { err "APK belum ada: $apk"; return 1; }
  info "install $apk"
  cmd install -r "$apk" || return 1
  sh1 'settings put secure enabled_accessibility_services com.mifi.helper/.UssdAccessibilityService' >/dev/null
  sh1 'settings put secure accessibility_enabled 1' >/dev/null
  sh1 'am startservice com.mifi.helper/.HttpApiService' >/dev/null
  ok "helper terpasang + service dinyalakan"
}

# ---------- selftest (tanpa device) ----------
selftest() {
  hr
  say "${BOLD}selftest${RST}"
  hr
  if resolve_adb; then ok "adb: $ADB"; else err "adb tidak ditemukan"; fi
  say "repo root : $ROOT"
  for f in scripts/deploy-web.sh scripts/build-helper.sh scripts/usb-ncm/enable-usb-ncm.sh web/index.html; do
    if [ -e "$ROOT/$f" ]; then ok "$f"; else warn "hilang: $f"; fi
  done
  say ""
  say "device saat ini:"
  list_devices | sed 's/^/  /'
  hr
}

# ---------- menu ----------
menu() {
  resolve_adb || return 1
  if ensure_device >/dev/null 2>&1; then
    DEV_LABEL="$(prop ro.product.model)"
  fi
  while true; do
    clear 2>/dev/null || true
    say "${BOLD}${MAG}MF800 · Ngoprek Modem${RST} ${DIM}— panel demo (Lo-Tech Meetup)${RST}"
    if [ -n "$SERIAL" ]; then
      say "device: ${GRN}${DEV_LABEL:-?}${RST} ${DIM}(${SERIAL})${RST}"
    else
      say "device: ${YEL}belum terdeteksi${RST}"
    fi
    hr
    say "  ${BOLD}1${RST}) Deteksi modem      ${DIM}model · root · RAN · sinyal · partisi${RST}"
    say "  ${BOLD}2${RST}) Switch mode        ${DIM}RAN: Auto / 3G / 4G -> AT^SYSCONFIG${RST}"
    say "  ${BOLD}3${RST}) Dump firmware      ${DIM}snapshot partisi → ./dumps/${RST}"
    say "  ${BOLD}4${RST}) Deploy web UI      ${DIM}push + chown root:root (persist)${RST}"
    say "  ${BOLD}d${RST}) Debug mode        ${DIM}aktifkan ADB/diag (funcNo 2001, reboot)${RST}"
    hr
    say "  ${BOLD}5${RST}) Buka web admin     ${DIM}adb forward :80 & :8080${RST}"
    say "  ${BOLD}6${RST}) Internet USB NCM   ${DIM}macOS dapat IP dari modem${RST}"
    say "  ${BOLD}7${RST}) Build + helper APK ${DIM}port 8080 (SMS/USSD/PTY)${RST}"
    say "  ${BOLD}8${RST}) Selftest           ${DIM}cek adb & file${RST}"
    say "  ${BOLD}r${RST}) Refresh device    ${BOLD}q${RST}) Keluar"
    hr
    printf 'pilih> '
    local opt; read -r opt || break
    case "$opt" in
      1|detect) detect_modem;;
      2|mode)   switch_mode_menu;;
      3|dump)   dump_firmware;;
      4|deploy) do_deploy;;
      d|D|debug) debug_mode 0;;
      5|web)    do_web;;
      6|ncm)    do_ncm;;
      7|helper) do_helper;;
      8|selftest) selftest;;
      r|R)      SERIAL=""; DEV_LABEL=""; ensure_device >/dev/null 2>&1;;
      q|Q|keluar|exit) break;;
      *) warn "pilihan tidak dikenal: $opt";;
    esac
    printf '\n%s' "${DIM}[tekan Enter]${RST} "
    read -r _ || break
  done
}

usage() {
  cat <<EOF
${BOLD}mf800.sh${RST} — panel demo Ngoprek Modem (MF800)

  ./scripts/mf800.sh                     menu interaktif
  ./scripts/mf800.sh detect              deteksi modem
  ./scripts/mf800.sh devices             daftar device adb
  ./scripts/mf800.sh mode auto|3g|4g|<n> switch RAN (AT^SYSCONFIG)
  ./scripts/mf800.sh debug [--yes]       aktifkan ADB/diag (funcNo 2001, reboot)
  ./scripts/mf800.sh dump [--all] [--force] [--only a,b]   dump partisi → ./dumps/
  ./scripts/mf800.sh deploy              deploy web UI + lockdown
  ./scripts/mf800.sh web                 adb forward + URL web admin
  ./scripts/mf800.sh ncm                 internet USB (CDC-NCM) ke macOS
  ./scripts/mf800.sh helper              build + install mifi-helper.apk
  ./scripts/mf800.sh selftest            cek adb & file

  opsi global: --serial <SN>   pilih device   |   --adb <path>
EOF
}

# ---------- dispatch ----------
resolve_adb

args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --serial|-s) SERIAL="${2:-}"; shift 2;;
    --adb)       ADB="${2:-}"; shift 2;;
    -h|--help|help) usage; exit 0;;
    *)           args+=("$1"); shift;;
  esac
done
set -- "${args[@]:-}"

ACTION="${1:-}"
case "$ACTION" in
  "")
    if [ -t 0 ]; then menu; else usage; fi
    ;;
  menu)      menu;;
  detect|status) ensure_device && detect_modem;;
  devices)   list_devices;;
  mode)      shift; ensure_device && set_ran_cli "${1:-}";;
  debug)
    shift
    yes=0
    [ "${1:-}" = "--yes" ] && yes=1
    debug_mode "$yes"
    ;;
  dump)
    shift
    FORCE=0; ONLY=""; ALL=0
    while [ $# -gt 0 ]; do
      case "$1" in
        --force|-f) FORCE=1; shift;;
        --only) ONLY="${2:-}"; shift 2;;
        --all|-a) ALL=1; shift;;
        *) shift;;
      esac
    done
    dump_firmware
    ;;
  deploy)    do_deploy;;
  web)       do_web;;
  ncm|usb-ncm) do_ncm;;
  helper)    do_helper;;
  selftest)  selftest;;
  *)         err "perintah tidak dikenal: $ACTION"; usage; exit 2;;
esac
