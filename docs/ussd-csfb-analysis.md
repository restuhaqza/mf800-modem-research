# Analisa Batasan USSD pada MF800 (UZ801 / MSM8916, V3.3.3)

Tanggal analisa: 2026-09-04. Semua bukti dikumpulkan live dari device via ADB (root).

## Ringkasan

USSD gagal karena kombinasi **(1) konfigurasi produk data-only di modem** dan
**(2) tidak tersedianya jalur CS/USSD di jaringan Indonesia (LTE-only)**.
Workaround lama "net_mode=1 (3G Only)" **sudah mati** karena 3G dimatikan nasional.

## Bukti (trace RIL, repro `*123#` via mifi-helper)

```
D/GSMPhone: dialing w/ mmi 'GsmMmiCode {State=PENDING action=* sc=123 poundString=*123#}'...
D/RILJ:     [4256]> SEND_USSD *******
D/RILJ:     [4256]< SEND_USSD              ← modem terima, tanpa error
D/RILJ:     [UNSL]< UNSOL_ON_USSD 2        ← INSTAN: USSD_MODE_NW_RELEASE (mode 2)
E/RILQ:     qmi_ril_... invalid android_unsol_resp id 11036   ← qcril kirim info tambahan, tak dikenal framework
```

- Dialog yang terbaca: `出现连接问题或 MMI 码无效` = string zh-CN
  `com.android.internal.R.string.mmiError` ("Connection problem or invalid MMI code"),
  dipicu `GsmMmiCode.onUssdFinishedError()` setelah NW_RELEASE.
- **Tidak ada prosedur CSFB sama sekali**: tidak ada RRC release/redirect, tidak ada
  perubahan RAT, tidak ada location update. Release terjadi < 1 detik.
  CSFB asli butuh 2–6 detik dan banjir log NAS. → kegagalan ada di **modem (MPSS)**,
  bukan di framework Android / web UI / mifi-helper.

## Status radio saat gagal

| Item | Nilai | Arti |
|---|---|---|
| `gsm.network.type` | LTE | camp di 4G |
| `mServiceState` | `... LTE LTE CSS not supported` | supplementary services tak didukung di RAT LTE |
| `gsm.current.phone-type` | 1 (GSM) | — |
| `persist.radio.jbims` / `calls.on.ims` / `csvt.enabled` | 0 / 0 / false | **IMS/VoLTE & CSVT mati total** |
| SMS | jalan | via NAS/SGs (SMS-only domain) |

## Firmware modem

- Partisi `modem` (vfat, 64 MB) berisi MPSS build:
  **`MPSS.DPM.1.0.1.c1-00121-M8936FAAAANUZM-1_20150907_230707`** (build 2015, target 8936/8916 shared).
- Mesin **CSFB ter-compile ada**: `emm_csfb_handler.c`, `mcccsfbsup.c`, `esm_onex_csfb.c`,
  NV `g2l_blind_redir_after_csfb_control`, `wcdma_rrc_fast_return_to_lte_after_csfb`, dll.
- Ada **MCFG carrier packs** (Airtel, AMX_NonVoLTE, ATT_VoLTE, ...) yang menulis NV:
  - `/nv/item_files/modem/mmode/sms_only` ← **flag produk data-only: voice/USSD CS dimatikan, SMS via NAS tetap jalan**
  - `/nv/item_files/modem/mmode/voice_domain_pref`
  - `/nv/item_files/modem/mmode/ue_usage_setting`
- Policyman XML default: `ue_mode → CSFB` (untuk SIM non-SGLTE) — jadi kebijakan *image* condong CSFB.
- `modemst1/2` (EFS tempat NV runtime disimpan) **terenkripsi** → nilai NV tak terbaca offline,
  namun gejala (SMS ok, USSD direlease instan oleh modem sendiri) konsisten dengan
  **voice domain = SMS-only** untuk produk MiFi data-only ini.
- Build AP: `eng..20230522:userdebug/test-keys`, `ro.secure=0` → firmware engineering,
  secure boot praktis tidak mengunci (komunitas UZ801 flash boot/system custom via EDL).

## Konteks jaringan (Indonesia, 2026)

- **3G dimatikan nasional** (Indosat selesai 2022/awal 2023) → CSFB ke UMTS mustahil.
- **2G masih ada tapi phase-out mulai 2026** (target pemerintah, progres per operator per wilayah).
- USSD di LTE hanya mungkin via IMS/VoLTE — firmware 2015 ini tidak punya IMS yang usable
  (stack IMS Qualcomm era KK + profil China; IMS APN/operator config Indosat tidak akan cocok).

## Kesimpulan akar masalah

1. Modem dikonfigurasi produk **data-only/SMS-only** (NV `mmode/sms_only` et al.) →
   modem me-release USSD sendirian tanpa mencoba CSFB.
2. Walau konfigurasi diubah ke voice-capable, **CSFB butuh target CS (2G)** — tinggal
   2G (semakin menyusut, phase-out 2026). Jalur IMS tidak realistis untuk firmware ini.
3. Jadi batasan ini *two-layer*: firmware (fixable, sulit) × jaringan (tidak bisa diubah).

## Opsi modifikasi (ranking risiko vs nilai)

| Route | Cara | Risiko | Catatan |
|---|---|---|---|
| 0. Tanpa firmware | Ganti fungsi USSD via SMS (cek pulsa dsb.) + API helper | nol | Paling pragmatis; sudah berjalan |
| 1. NV via DIAG/QMI | Aktifkan komposisi USB dengan interface DIAG → tulis NV `sms_only=0`, `voice_domain_pref=CS_PREF`, `ue_usage_setting=voice-centric` (QPST/edl) | sedang | Tidak flash partisi; modemst ada mirror; **prasyarat: ada 2G di area** |
| 2. Patch modem.img | Ubah mcfg/policy di image modem (FAT16, file terbaca) → flash via EDL (`edl` tool + firehose MSM8916, komunitas terbukti) | tinggi | Patch dalam MPSS/mcfg; salah → modem crash loop, recover via EDL dump |
| 3. Swap firmware varian lain | Flash modem/system varian phone-ish | sangat tinggi | AGENTS.md sudah memperingatkan: beda varian = risiko brick |
| 4. Ganti perangkat untuk USSD | GSM gateway / HP Android terpisah | nol | Jika USSD benar-benar wajib |

## Eksperimen lanjutan yang murah (belum dijalankan)

1. **Tes keberadaan 2G**: set `net_mode=1` (3G Only) → jika tidak ada service berarti
   WCDMA memang mati; lalu coba RAT pref GSM-only via QMI (di luar UI) untuk cek camp GERAN.
   Jika bisa camp di 2G → Route 1 layak dicoba (CSFB target ada).
2. **Cek komposisi USB DIAG**: `getprop sys.usb.config` / ubah ke komposisi ber-diag
   (`sys.usb.config diag,adb` umum di build qcom) → verifikasi interface DIAG muncul.
3. Baca nilai NV runtime via DIAG (`voice_domain_pref`, `sms_only`) untuk konfirmasi langsung
   hipotesis SMS-only sebelum menyentuh apa pun.

## File bukti

- `/tmp/radio_after_ussd.log` — dump logcat radio lengkap saat repro (sesi analisa).
- Dump partisi (disimpan di luar repo: `~/mf800-firmware-private/` — `modem.img`,
  `modemst1/2.img`, dst.) — sumber analisa offline.

## Addendum (verifikasi lanjutan, sesi yang sama): apakah modem punya 2G?

Ditemukan channel AT command: binary `/system/bin/modem_at <AT>` (dipakai
`WanDataController.sendAtBySmd` di MifiService.apk; bisa dipanggil langsung via ADB root).

```
# adb shell 'modem_at AT^SYSCONFIG?'
^SYSCONFIG: 11,0,1,2                      ← mode Auto saat ini

# adb shell 'modem_at AT+COPS=?'          ← scan PLMN nyata
+COPS: (2,"INDOSATOOREDOO","INDOSAT","51001",7),
       (3,"IND TELKOMSEL","T-SEL","51010",7),
       (3,"3","3","51089",7),
       (1,"510 09","510 09","51009",7),
       (3,"IND XL","XL","51011",7)
```

**Semua operator terlihat dengan AcT=7 (E-UTRAN) saja** — nol sel GSM (AcT=0),
nol UMTS (AcT=2). Tes `AT^SYSCONFIG=<n>` hanya menerima nilai {2, 11, 12, 14}
(persis konstanta di APK): 1 (GSM-only), 3, 10, 13, 0 ditolak ERROR/CME.
Tes net_mode=1 ("3G Only" = WCDMA-only) → out of service total.

### Kesimpulan addendum

- Praktis: **modem ini LTE-only di area ini** — 3G mati nasional, 2G tidak ada
  sinyal dari operator mana pun di lokasi ini (dan phase-out 2G nasional mulai 2026).
- Firmware punya stack GSM (string `GSMSRCH`, band `GSM_PGSM/EGSM/DCS/PCS`), tapi
  tidak ada mode AT yang bisa memaksa camp GSM → jalur CSFB-ke-2G **mati dua lapis**.
- Jadi Route 1/2 (NV/policy CSFB) **tidak lagi layak** di Indonesia: walau konfigurasi
  voice-domain diubah, tidak ada target CS. Satu-satunya jalur USSD di LTE adalah IMS
  (tidak realistis untuk firmware 2015 ini). **USSD di modem ini = dead end**;
  gunakan jalur SMS via mifi-helper untuk fungsi operator (cek pulsa dsb.).
