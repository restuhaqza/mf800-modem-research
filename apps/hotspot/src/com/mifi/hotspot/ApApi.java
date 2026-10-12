package com.mifi.hotspot;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * API hotspot yang disembunyikan dari SDK publik (SystemApi/hidden):
 *   setWifiApEnabled(), isWifiApEnabled(), setWifiApConfiguration(), getWifiApConfiguration()
 * Semuanya diakses lewat refleksi supaya app bisa dikompilasi dengan android.jar biasa.
 * Di Android 10+ method ini dihapus; backend akan gagal dengan pesan jelas.
 */
final class ApApi {

    private ApApi() {}

    static WifiConfiguration buildConfig(String ssid, String pass, int bandCode) {
        WifiConfiguration cfg = new WifiConfiguration();
        cfg.SSID = ssid;
        if (pass != null && pass.length() >= 8) {
            cfg.preSharedKey = pass;
            keyMgmt(cfg, "WPA2_PSK", 4);
        } else {
            keyMgmt(cfg, "NONE", 0);
        }
        setPublicField(cfg, "apBand", Integer.valueOf(bandCode));
        return cfg;
    }

    private static void keyMgmt(WifiConfiguration cfg, String name, int fallback) {
        int value = fallback;
        try {
            Class<?> k = Class.forName("android.net.wifi.WifiConfiguration$KeyMgmt");
            value = k.getField(name).getInt(null);
        } catch (Throwable ignored) {
        }
        try {
            cfg.allowedKeyManagement.set(value);
        } catch (Throwable ignored) {
        }
    }

    static void setConfig(WifiManager wm, WifiConfiguration cfg) throws Exception {
        Method m = WifiManager.class.getDeclaredMethod("setWifiApConfiguration", WifiConfiguration.class);
        m.setAccessible(true);
        Object r = m.invoke(wm, cfg);
        if (r instanceof Boolean && !((Boolean) r)) {
            throw new RuntimeException("setWifiApConfiguration() = false");
        }
    }

    static WifiConfiguration getConfig(WifiManager wm) {
        try {
            Method m = WifiManager.class.getDeclaredMethod("getWifiApConfiguration");
            m.setAccessible(true);
            return (WifiConfiguration) m.invoke(wm);
        } catch (Throwable t) {
            return null;
        }
    }

    /** true kalau permintaan diterima. Melempar exception kalau method tidak ada / ditolak. */
    static boolean setEnabled(WifiManager wm, WifiConfiguration cfg, boolean on) throws Exception {
        Method m = WifiManager.class.getDeclaredMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
        m.setAccessible(true);
        Object r = m.invoke(wm, cfg, Boolean.valueOf(on));
        return !(r instanceof Boolean) || (Boolean) r;
    }

    static boolean isEnabled(WifiManager wm) {
        try {
            Method m = WifiManager.class.getDeclaredMethod("isWifiApEnabled");
            m.setAccessible(true);
            Object r = m.invoke(wm);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void setPublicField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getField(name);
            f.set(target, value);
        } catch (Throwable ignored) {
        }
    }
}
