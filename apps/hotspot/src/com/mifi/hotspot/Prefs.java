package com.mifi.hotspot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** Penyimpanan setelan (SharedPreferences), tanpa dependency apa pun. */
final class Prefs {

    static final int DEFAULT_PORT = 8090;
    static final String DEFAULT_PIN = "admin";

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("hotspot", Context.MODE_PRIVATE);
    }

    static String ssid(Context c) {
        return sp(c).getString("ssid", defaultSsid());
    }

    static String pass(Context c) {
        return sp(c).getString("pass", "12345678");
    }

    static String band(Context c) {
        return sp(c).getString("band", "auto");
    }

    /** SSID asli sistem, hasil bacaan automasi (tanpa root tidak bisa dibaca via API). */
    static String systemSsid(Context c) {
        return sp(c).getString("system_ssid", "");
    }

    static void setSystemSsid(Context c, String ssid) {
        sp(c).edit().putString("system_ssid", ssid == null ? "" : ssid).apply();
    }

    /** State hotspot yang terakhir dibaca/di-set langsung dari sistem (bukan perkiraan). */
    static boolean systemOn(Context c) {
        return sp(c).getBoolean("system_on", false);
    }

    static long systemOnAt(Context c) {
        return sp(c).getLong("system_on_at", 0L);
    }

    static void setSystemOn(Context c, boolean on) {
        sp(c).edit()
                .putBoolean("system_on", on)
                .putLong("system_on_at", System.currentTimeMillis())
                .apply();
    }

    static String pin(Context c) {
        return sp(c).getString("pin", DEFAULT_PIN);
    }

    static int port(Context c) {
        return sp(c).getInt("port", DEFAULT_PORT);
    }

    static boolean autostart(Context c) {
        return sp(c).getBoolean("autostart", false);
    }

    static void save(Context c, String ssid, String pass, String band,
                     String pin, int port, boolean autostart) {
        sp(c).edit()
                .putString("ssid", ssid)
                .putString("pass", pass)
                .putString("band", band)
                .putString("pin", pin)
                .putInt("port", port)
                .putBoolean("autostart", autostart)
                .apply();
    }

    static void setLastBackend(Context c, String id) {
        sp(c).edit().putString("last_backend", id).apply();
    }

    static String lastBackend(Context c) {
        return sp(c).getString("last_backend", "-");
    }

    static void setDesiredOn(Context c, boolean on) {
        sp(c).edit().putBoolean("desired_on", on).apply();
    }

    static boolean desiredOn(Context c) {
        return sp(c).getBoolean("desired_on", false);
    }

    static String defaultSsid() {
        String model = Build.MODEL == null ? "" : Build.MODEL.replaceAll("[^A-Za-z0-9]", "");
        if (model.length() > 12) model = model.substring(0, 12);
        return "Mifi-" + (model.isEmpty() ? "AP" : model);
    }
}
