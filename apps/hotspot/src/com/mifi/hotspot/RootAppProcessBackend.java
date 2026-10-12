package com.mifi.hotspot;

import android.content.Context;

/**
 * Jalur root: menjalankan TetherCli lewat `app_process` sebagai uid 0.
 *
 * Alasan: permission TETHER_PRIVILEGED = signature|privileged, jadi app biasa
 * ditolak. Tapi proses dengan uid 0 lolos semua cek permission, sehingga
 * startTethering()/stopTethering() bisa dipanggil dari proses root.
 *
 * Perintah: su -c "CLASSPATH=<apk> app_process -Djava.class.path=<apk> /system/bin com.mifi.hotspot.TetherCli <aksi>"
 */
final class RootAppProcessBackend implements TetherBackend {

    private final Context ctx;
    private volatile String error = "";

    RootAppProcessBackend(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public String id() {
        return "root-app_process";
    }

    public String label() {
        return "app_process sebagai root (uid 0)";
    }

    public String lastError() {
        return error;
    }

    public boolean available() {
        return Shell.hasRoot();
    }

    public boolean start(Cfg cfg) {
        return invoke("start", cfg);
    }

    public boolean stop() {
        return invoke("stop", null);
    }

    boolean setConfig(Cfg cfg) {
        return invoke("setconfig", cfg);
    }

    /** status() mengembalikan JSON mentah dari TetherCli, atau null kalau gagal. */
    String statusJson() {
        error = "";
        Shell.Res r = Shell.su(cliCommand("status", null), 20000);
        String out = r.out.trim();
        int brace = out.indexOf('{');
        if (brace >= 0) {
            int end = out.lastIndexOf('}');
            if (end > brace) return out.substring(brace, end + 1);
        }
        error = r.best();
        if (error.length() > 400) error = error.substring(0, 400);
        return null;
    }

    private boolean invoke(String action, Cfg cfg) {
        error = "";
        Shell.Res r = Shell.su(cliCommand(action, cfg), 25000);
        String out = r.out.trim();
        if (out.contains("\"ok\":true") || out.contains("\"ok\": true")) {
            return true;
        }
        String best = r.best();
        int brace = best.indexOf('{');
        if (brace >= 0) {
            int end = best.lastIndexOf('}');
            if (end > brace) best = best.substring(brace, end + 1);
        }
        error = best.isEmpty() ? ("exit " + r.code) : best;
        if (error.length() > 400) error = error.substring(0, 400);
        return false;
    }

    private String cliCommand(String action, Cfg cfg) {
        String apk = ctx.getPackageCodePath();
        StringBuilder sb = new StringBuilder();
        sb.append("CLASSPATH=").append(apk)
                .append(" app_process -Djava.class.path=").append(apk)
                .append(" /system/bin com.mifi.hotspot.TetherCli ").append(action);
        if (cfg != null) {
            sb.append(" '").append(sanitize(cfg.ssid)).append("'")
                    .append(" '").append(sanitize(cfg.pass)).append("'")
                    .append(" ").append(cfg.band);
        }
        return sb.toString();
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("'", "").replace("\\", "").replace("\n", "").replace("\r", "");
    }
}
