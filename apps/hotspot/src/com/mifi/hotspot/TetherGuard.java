package com.mifi.hotspot;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;

/** Validasi input + utilitas jaringan kecil. */
final class TetherGuard {

    private TetherGuard() {}

    /** Kembalikan pesan kesalahan, atau null kalau input valid. */
    static String warn(Context c, String ssid, String pass, String pin) {
        if (ssid == null || ssid.trim().isEmpty()) {
            return "SSID tidak boleh kosong";
        }
        if (ssid.length() > 32) {
            return "SSID maksimal 32 karakter";
        }
        if (pass != null && !pass.isEmpty() && pass.length() < 8) {
            return "password hotspot minimal 8 karakter (standar WPA2)";
        }
        if (pin == null || pin.length() < 4) {
            return "PIN API minimal 4 karakter";
        }
        return null;
    }

    /** IP perangkat saat ini (STA). null kalau tidak tersedia. */
    static String localIp(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) return null;
            return Formatter.formatIpAddress(ip);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * URL untuk perangkat yang tersambung ke HOTSPOT.
     * Interface AP tidak terlihat dari HP (Android menaruh alamatnya di namespace
     * terpisah), jadi gateway diturunkan dari IP client yang muncul di tabel ARP:
     * gateway tethering Android selalu X.Y.Z.1.
     */
    static String hotspotUrl(java.util.List<Client> clients, int port) {
        if (clients != null) {
            for (Client c : clients) {
                String ip = c.ip == null ? "" : c.ip.trim();
                if (ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                    return "http://" + ip.substring(0, ip.lastIndexOf('.')) + ".1:" + port + "/";
                }
            }
        }
        return null;
    }
}
