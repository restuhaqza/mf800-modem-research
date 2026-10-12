package com.mifi.hotspot;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

/**
 * Jalur app biasa (tanpa root).
 *
 * Di Android 8/9 method setWifiApEnabled() dijaga permission NETWORK_SETTINGS
 * (signature|privileged), jadi app normal akan kena SecurityException. Jalur ini
 * hanya berhasil kalau app dipasang privileged. Kalau gagal (kasus umum),
 * TetherController otomatis lanjut ke backend root, lalu ke Setelan.
 */
final class PrivilegedBackend implements TetherBackend {

    private final Context ctx;
    private volatile String error = "";

    PrivilegedBackend(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public String id() {
        return "privileged";
    }

    public String label() {
        return "App privileged (permission NETWORK_SETTINGS)";
    }

    public String lastError() {
        return error;
    }

    public boolean available() {
        return true;
    }

    public boolean start(Cfg cfg) {
        error = "";
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            WifiConfiguration wcfg = ApApi.buildConfig(cfg.ssid, cfg.pass, cfg.bandCode());
            try {
                ApApi.setConfig(wm, wcfg);
            } catch (Throwable ignored) {
                // konfigurasi opsional
            }
            boolean ok = ApApi.setEnabled(wm, wcfg, true);
            if (!ok) {
                error = "setWifiApEnabled() menolak permintaan";
                return false;
            }
            return true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }

    public boolean stop() {
        error = "";
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            ApApi.setEnabled(wm, null, false);
            return true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }
}
