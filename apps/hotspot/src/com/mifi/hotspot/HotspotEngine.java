package com.mifi.hotspot;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Pusat kendali. Dipakai bersama oleh Activity, Service (HTTP API) dan Quick Settings tile.
 *
 * Urutan backend: privileged -> root (app_process) -> Setelan (fallback manual).
 */
final class HotspotEngine {

    private static final String TAG = "MifiHotspot";

    interface Logger {
        void log(String line);
    }

    static final class Status {
        boolean enabled;
        boolean verified;      // true kalau hasil baca langsung dari sistem
        String ssid = "";      // nama terbaik yang diketahui (sistem diutamakan)
        String ssidApp = "";   // nilai yang disimpan app
        String ssidSystem = "";// nama asli hasil bacaan dari layar Setelan
        String backend = "-";
        String error = "";
        long checkedAt;
        List<Client> clients = new ArrayList<Client>();
    }

    private static volatile HotspotEngine inst;

    static HotspotEngine get(Context c) {
        if (inst == null) {
            synchronized (HotspotEngine.class) {
                if (inst == null) inst = new HotspotEngine(c.getApplicationContext());
            }
        }
        return inst;
    }

    private final Context ctx;
    private final Capabilities caps;
    private final PrivilegedBackend privileged;
    private final RootAppProcessBackend root;
    private final AccessibilityBackend accessibility;
    private final SettingsBackend settings;
    private final List<TetherBackend> chain = new ArrayList<TetherBackend>();
    private final Deque<String> log = new ArrayDeque<String>();
    private final Object lock = new Object();

    private Status cached;
    private long cacheAt = 0L;

    private HotspotEngine(Context c) {
        ctx = c;
        caps = new Capabilities(c);
        privileged = new PrivilegedBackend(c);
        root = new RootAppProcessBackend(c);
        accessibility = new AccessibilityBackend(c);
        settings = new SettingsBackend(c);
        chain.add(privileged);
        chain.add(root);
        chain.add(accessibility);
        chain.add(settings);
    }

    Capabilities caps() {
        return caps;
    }

    // -- aksesibilitas ---------------------------------------------------------------

    /** true kalau layanan aksesibilitas kita sudah dicentang pengguna di Setelan. */
    boolean accessibilityEnabled() {
        try {
            String s = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null
                    && s.contains(ctx.getPackageName())
                    && s.contains(HotspotAccessibilityService.class.getSimpleName());
        } catch (Throwable t) {
            return false;
        }
    }

    boolean accessibilityConnected() {
        return HotspotAccessibilityService.isConnected();
    }

    void openAccessibilitySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            log("gagal buka Setelan aksesibilitas: " + t.getMessage());
        }
    }

    /** Minta baca SSID asli dari sistem (lewat automasi, atau langsung kalau root). */
    boolean readSystemSsid() {
        synchronized (lock) {
            if (root.available()) {
                invalidate();
                return true;
            }
            return accessibility.readSsid();
        }
    }

    // -- operasi ---------------------------------------------------------------------

    boolean start() {
        Cfg cfg = new Cfg(Prefs.ssid(ctx), Prefs.pass(ctx), Prefs.band(ctx));
        return start(cfg);
    }

    boolean start(Cfg cfg) {
        synchronized (lock) {
            log("mulai hotspot: ssid='" + cfg.ssid + "', band=" + cfg.band);
            for (TetherBackend b : chain) {
                if (!b.available()) {
                    log("lewati " + b.id() + " (tidak tersedia)");
                    continue;
                }
                log("coba " + b.id() + " ...");
                boolean ok;
                try {
                    ok = b.start(cfg);
                } catch (Throwable t) {
                    ok = false;
                }
                if (ok) {
                    Prefs.setLastBackend(ctx, b.id());
                    if (!"settings".equals(b.id())) Prefs.setDesiredOn(ctx, true);
                    log("✓ hotspot aktif via " + b.id());
                    invalidate();
                    return true;
                }
                log("✗ " + b.id() + " gagal: " + b.lastError());
            }
            log("semua jalur otomatis gagal — buka Setelan tethering untuk mode manual");
            invalidate();
            return false;
        }
    }

    boolean stop() {
        synchronized (lock) {
            log("matikan hotspot");
            boolean any = false;
            for (TetherBackend b : chain) {
                if (!b.available()) continue;
                if ("settings".equals(b.id())) continue;
                try {
                    if (b.stop()) {
                        log("✓ dimatikan via " + b.id());
                        any = true;
                        break;
                    }
                    log("✗ " + b.id() + ": " + b.lastError());
                } catch (Throwable t) {
                    log("✗ " + b.id() + ": " + t.getMessage());
                }
            }
            if (!any) log("tidak ada backend yang berhasil mematikan hotspot");
            Prefs.setDesiredOn(ctx, false);
            invalidate();
            return any;
        }
    }

    void openSettings() {
        settings.start(null);
    }

    void saveConfig(Cfg cfg, String pin, int port, boolean autostart) {
        Prefs.save(ctx, cfg.ssid, cfg.pass, cfg.band, pin, port, autostart);
        log("konfigurasi disimpan");
    }

    void invalidate() {
        cacheAt = 0L;
    }

    // -- status ----------------------------------------------------------------------

    Status status() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (cached != null && now - cacheAt < 2500L) return cached;

            Status s = new Status();
            s.checkedAt = now;
            s.backend = Prefs.lastBackend(ctx);

            if (root.available()) {
                String json = root.statusJson();
                if (json != null) {
                    try {
                        JSONObject o = new JSONObject(json);
                        s.enabled = o.optBoolean("enabled", false);
                        s.ssidSystem = o.optString("ssid", "");
                        JSONArray arr = o.optJSONArray("clients");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject c = arr.getJSONObject(i);
                                s.clients.add(new Client(
                                        c.optString("ip"),
                                        c.optString("mac"),
                                        c.optString("name"),
                                        c.optString("source")));
                            }
                        }
                        s.verified = true;
                    } catch (Throwable t) {
                        s.error = "parse status: " + t.getMessage();
                    }
                } else {
                    s.error = root.lastError();
                }
            }

            if (!s.verified) {
                s.ssidSystem = Prefs.systemSsid(ctx);
                if (Prefs.systemOnAt(ctx) > 0L) {
                    // pernah dibaca langsung dari layar Setelan (tombol "Baca nama WiFi")
                    s.enabled = Prefs.systemOn(ctx);
                    s.verified = true;
                } else {
                    s.enabled = Prefs.desiredOn(ctx);
                }
                // saat hotspot mati, entri ARP berasal dari WiFi biasa -> jangan tampilkan
                s.clients = s.enabled ? filterClients(Client.readLocal()) : new ArrayList<Client>();
            } else {
                s.clients = filterClients(s.clients);
            }

            s.ssidApp = Prefs.ssid(ctx);
            s.ssid = s.ssidSystem.isEmpty() ? s.ssidApp : s.ssidSystem;

            cached = s;
            cacheAt = now;
            return s;
        }
    }

    /**
     * Buang entri yang sebenarnya berasal dari WiFi biasa (STA), bukan client hotspot.
     * Tanpa ini, router WiFi yang sedang tersambung oleh HP ikut tampil sebagai "client"
     * dan URL hotspot jadi salah.
     */
    private List<Client> filterClients(List<Client> in) {
        String staIp = TetherGuard.localIp(ctx);
        if (staIp == null || !staIp.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return in;
        String staPrefix = staIp.substring(0, staIp.lastIndexOf('.') + 1);
        List<Client> out = new ArrayList<Client>();
        for (Client c : in) {
            if (c.ip != null && c.ip.startsWith(staPrefix)) continue;
            out.add(c);
        }
        return out;
    }

    // -- log -------------------------------------------------------------------------
    void log(String line) {
        String stamped = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()) + "  " + line;
        android.util.Log.i(TAG, line);
        synchronized (log) {
            log.addFirst(stamped);
            while (log.size() > 120) log.removeLast();
        }
    }

    String logText() {
        StringBuilder sb = new StringBuilder();
        synchronized (log) {
            for (String l : log) sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
