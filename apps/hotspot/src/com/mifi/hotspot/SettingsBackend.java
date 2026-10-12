package com.mifi.hotspot;

import android.content.Context;

/**
 * Fallback terakhir: Android tidak mengizinkan app menyalakan hotspot, jadi kita
 * buka langsung layar tethering (component device-specific lebih dulu, baru action).
 * Ini tetap berguna walau tanpa aksesibilitas — pengguna tinggal menggeser sakelarnya.
 */
final class SettingsBackend implements TetherBackend {

    private final Context ctx;
    private volatile String error = "";

    SettingsBackend(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public String id() {
        return "settings";
    }

    public String label() {
        return "Buka layar Setelan tethering (manual)";
    }

    public String lastError() {
        return error;
    }

    public boolean available() {
        return true;
    }

    public boolean start(Cfg cfg) {
        return open();
    }

    public boolean stop() {
        return open();
    }

    private boolean open() {
        error = "";
        if (TetherScreens.open(ctx)) return true;
        error = "layar tethering tidak ditemukan";
        return false;
    }
}
