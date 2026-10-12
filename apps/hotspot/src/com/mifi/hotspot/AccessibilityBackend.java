package com.mifi.hotspot;

import android.content.Context;

/**
 * Backend tanpa root: minta HotspotAccessibilityService membuka Setelan tethering
 * dan mengklik sakelar hotspot. requestToggle() asinkron — backend langsung
 * mengembalikan true kalau permintaan diterima, dan hasil sebenarnya muncul di log.
 */
final class AccessibilityBackend implements TetherBackend {

    private final Context ctx;
    private volatile String error = "";

    AccessibilityBackend(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public String id() {
        return "accessibility";
    }

    public String label() {
        return "Automasi Setelan lewat aksesibilitas (tanpa root)";
    }

    public String lastError() {
        return error;
    }

    public boolean available() {
        return HotspotAccessibilityService.isConnected();
    }

    public boolean start(Cfg cfg) {
        return request(true);
    }

    public boolean stop() {
        return request(false);
    }

    /** Minta HotspotAccessibilityService membaca SSID asli dari layar Setelan. */
    boolean readSsid() {
        error = "";
        HotspotAccessibilityService svc = HotspotAccessibilityService.get();
        if (svc == null) {
            error = "layanan aksesibilitas belum aktif";
            return false;
        }
        svc.requestRead();
        return true;
    }

    private boolean request(boolean on) {
        error = "";
        HotspotAccessibilityService svc = HotspotAccessibilityService.get();
        if (svc == null) {
            error = "layanan aksesibilitas belum aktif";
            return false;
        }
        svc.requestToggle(on);
        return true;
    }
}
