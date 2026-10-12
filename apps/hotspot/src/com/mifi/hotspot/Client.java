package com.mifi.hotspot;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** Satu perangkat yang terhubung ke hotspot. */
final class Client {

    final String ip;
    final String mac;
    final String name;
    final String source;

    Client(String ip, String mac, String name, String source) {
        this.ip = ip == null ? "" : ip;
        this.mac = mac == null ? "" : mac;
        this.name = name == null ? "" : name;
        this.source = source == null ? "" : source;
    }

    /**
     * Pembacaan tanpa root: hanya tabel tetangga kernel. Cukup untuk daftar IP/MAC
     * saat app tidak punya akses ke lease dnsmasq.
     */
    static List<Client> readLocal() {
        List<Client> out = new ArrayList<Client>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/arp"));
            String line;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] f = line.trim().split("\\s+");
                if (f.length < 6) continue;
                String dev = f[5];
                if (!isApInterface(dev)) continue;
                if (!looksLikeMac(f[3]) || "00:00:00:00:00:00".equals(f[3])) continue;
                out.add(new Client(f[0], f[3], "", "arp"));
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    static boolean isApInterface(String dev) {
        if (dev == null) return false;
        String d = dev.toLowerCase();
        return d.startsWith("wlan") || d.startsWith("ap") || d.startsWith("swlan") || d.startsWith("softap");
    }

    static boolean looksLikeMac(String s) {
        return s != null && s.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}");
    }
}
