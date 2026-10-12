# Alpine Linux (`apk`) via chroot — package manager untuk terminal MF800

Menambahkan **package manager Linux sungguhan** ke terminal modem: sebuah
Alpine Linux (armv7) minirootfs di `/data/local/alpine`, dijalankan lewat
`chroot` sebagai root. Di dalamnya `apk` adalah package manager
(±22.000 paket: `bash`, `git`, `python3`, `htop`, `nano`, `curl`, …).

```
terminal web  →  root  →  alpine  →  apk add <paket>
```

## Kenapa chroot (bukan proot)

- Terminal app jalan sebagai `untrusted_app` dengan `CapBnd=0`, jadi tak bisa
  root dari sisi app.
- Tapi kita punya **root PTY channel** (`scripts/root-shell/`) yang dijalankan
  `init` saat boot. Dari sana `busybox chroot` diizinkan SELinux (terverifikasi).
- chroot = nol overhead (bukan ptrace), jadi cepat di RAM 398 MB.

Kenapa bukan Entware/opkg: pada firmware ini `opkg` mengalami heap-corruption
(`double free`/segfault) saat memuat index besar, sehingga banyak paket gagal.
`apk` tidak terkena masalah itu.

## Prasyarat

Root channel harus terpasang lebih dulu (memberi shell uid 0 + `busybox chroot`):

```bash
./scripts/root-shell/install.sh
```

## Pasang

```bash
./scripts/alpine-chroot/install.sh
# pin versi lain bila perlu:
ALPINE_VER=3.20.3 ./scripts/alpine-chroot/install.sh
```

Installer: mengunduh minirootfs resmi (verifikasi SHA-256), push & ekstrak ke
`/data/local/alpine`, memasang wrapper `alpine` ke `/data/local/mifi/alpine`
(juga di-`ln` ke `/opt/bin/alpine`), dan menulis `/etc/profile` + `resolv.conf`.

## Pakai

Di terminal web:

```sh
root                      # masuk shell root
alpine                    # masuk Alpine (interactive)
apk update
apk add git python3 htop  # install paket
apk del <paket>; apk upgrade
```

Satu perintah tanpa interactive:

```sh
alpine apk add --no-cache curl
```

## Detail teknis

- **Rootfs**: `/data/local/alpine` (persisten di `/data`). Factory reset
  menghapusnya → jalankan `install.sh` lagi.
- **Pseudo-FS**: `/proc`, `/sys`, `/dev`, `/dev/pts` di-bind ke dalam chroot
  setiap kali `alpine` dipanggil (hilang saat reboot, jadi selalu dibuat ulang).
- **DNS**: `/etc/resolv.conf` = `127.0.0.1` (dnsmasq modem) + `8.8.8.8`.
- **Jaringan**: pakai netns host; egress via `rmnet0` (IP seluler) atau `br0`.
- **Repo apk**: `https://dl-cdn.alpinelinux.org/alpine/v3.20/{main,community}`
  (bisa diedit di `/data/local/alpine/etc/apk/repositories`).
- **Ukuran**: rootfs ±5,4 MB; paket masuk ke rootfs yang sama.

## Berkas

| Berkas | Fungsi |
|---|---|
| `install.sh` | unduh + pasang rootfs dan wrapper |
| `alpine` | wrapper: bind-mount pseudo-FS, set PATH/DNS, `chroot` |
| `profile` | `/etc/profile` di dalam rootfs (PATH, PS1) |
| `.cache/` | cache minirootfs (di-`gitignore`) |
