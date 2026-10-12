# mifi-agent — agen AI mungil di dalam modem MF800

Agen "coding assistant" seukuran modem: berjalan **langsung di dalam** MF800
(Android 4.4.4, armv7, libc bionic) dan dipakai dari terminal web
(`web/terminal.html`). Ia bisa **menjalankan perintah shell, membaca/menulis file,
dan mendaftar direktori** di device lewat function calling, dengan LLM
DeepSeek sebagai otaknya.

```
  terminal web modem  ──►  mifi-agent  ──►  https://api.deepseek.com
        (PTY, uid app)        (static arm)      (deepseek-flash, tool calls)
```

## Kenapa bukan OpenCode langsung?

OpenCode tidak bisa dipasang di device ini — sudah dicoba dan gagal di tiga titik
independen:

| # | Percobaan | Hasil di MF800 |
|---|---|---|
| 1 | Installer resmi `opencode.ai/install` | `Unsupported OS/Arch: linux/armv7l` (hanya x64/arm64) |
| 2 | Jalankan binary `opencode-linux-arm64` | `/system/bin/sh: not executable: magic 7F45` (ELF 64-bit di sistem 32-bit) |
| 3 | Prasyarat installer | tak ada `bash`/`node`/`python`; bahkan `uname`/`tr`/`tail` absen di toolbox 4.4 |

Bahkan seandainya ada build 32-bit, binary OpenCode menautkan glibc
(`/lib/ld-linux-*.so`), sedangkan Android memakai bionic. Karena itu kita bikin
ulang versi kecilnya dengan **Go + stdlib saja**, dibangun **static** — pendekatan
yang sama dengan `speedtest-go` di repo ini.

## Cara pakai

```bash
./scripts/build-mini-agent.sh      # -> apps/mini-agent/out/mifi-agent-linux-arm
./scripts/install-mini-agent.sh    # push ke <helper filesDir>/bin

# sekalian menulis API key ke device:
MIFI_AI_KEY=sk-... ./scripts/install-mini-agent.sh
```

Di terminal web modem:

```sh
mifi-agent --selftest        # uji tool tanpa API (tak butuh key)
mifi-agent --config          # lihat provider/model/key (key disamarkan)
mifi-agent "cek suhu & sinyal modem, jelaskan"   # sekali jalan
mifi-agent                   # mode ngobrol (REPL): /help /reset /exit
```

## Konfigurasi

Presedensi: **env > `$HOME/.mifi-agent.json`** (`$HOME` = `<helper filesDir>/home`,
tempat terminal di-`chdir`).

| Env | Default | Arti |
|---|---|---|
| `MIFI_AI_PROVIDER` | `deepseek` | preset `deepseek` / `openai` |
| `MIFI_AI_BASE_URL` | `https://api.deepseek.com` | base URL OpenAI-compatible |
| `MIFI_AI_KEY` | — | API key (wajib untuk chat) |
| `MIFI_AI_MODEL` | `deepseek-flash` | model (preset deepseek) |
| `MIFI_AI_SYSTEM` | (bawaan) | override system prompt |

Contoh `$HOME/.mifi-agent.json`:

```json
{ "provider": "deepseek", "model": "deepseek-flash", "api_key": "sk-..." }
```

DeepSeek juga menyediakan `deepseek-v4-pro` (lebih kuat) — cukup `mifi-agent -m deepseek-v4-pro`.
Endpoint-nya OpenAI-compatible, jadi provider lain (OpenAI, gateway apa pun) bisa
lewat `-p openai` atau `MIFI_AI_BASE_URL`.

## Tool yang tersedia

| Tool | Fungsi |
|---|---|
| `run_command` | jalankan perintah via `/system/bin/sh -c` (stdout+stderr+exit code) |
| `read_file` | baca file teks (maks 16 KB) |
| `write_file` | tulis/overwrite file (parent dir dibuat otomatis) |
| `list_dir` | daftar isi direktori |

Semuanya berjalan sebagai **uid app** (bukan root) — sama seperti terminal web.

## Catatan teknis

- **TLS & CA store.** Binary Go `GOOS=linux` tidak membaca `/system/etc/security/cacerts`
  (itu hanya berlaku untuk `GOOS=android`, yang justru butuh cgo + simbol API 21).
  Wrapper `mifi-agent` mengekspor `SSL_CERT_DIR` sebelum exec engine `mifi-agent.bin`.
- **DNS.** Tak ada `/etc/resolv.conf`, jadi resolver Go jatuh ke `127.0.0.1:53`
  (dnsmasq modem) — sama seperti `speedtest`.
- **Persistensi.** Dipasang di `<helper filesDir>/bin` dengan owner app uid, jadi
  bertahan setelah reboot.
- **Zero dependency.** Cuma stdlib Go → `CGO_ENABLED=0` murni, tak ada soal libc.

## Uji cepat (tanpa API key)

```bash
# di modem:
mifi-agent --selftest
```
