package com.mifi.hotspot;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Automasi tanpa root: membuka layar tethering lalu
 *  - mengklik sakelar hotspot (cek isChecked() dulu), dan
 *  - memanen nama WiFi (SSID) asli dari layar, karena tanpa root API
 *    getWifiApConfiguration() tidak bisa dipakai.
 *
 * Aman karena hanya bekerja saat ada PERMINTAAN aktif (tombol/tile/API),
 * event dibatasi ke paket Setelan, dan tidak pernah spontan.
 *
 * Device acuan (Sony XZ1 / SOV36, Android 9), diverifikasi lewat dump UI:
 * layar com.sonymobile.wifi/.hotspot.WifiApSettings, sakelar
 * com.sonymobile.wifi:id/switch_bar_switch, tanpa dialog konfirmasi,
 * nama di node "Network name: <SSID>\\nSecurity: ...".
 */
public final class HotspotAccessibilityService extends AccessibilityService {

    private static volatile HotspotAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile Boolean want;          // permintaan toggle (null = tidak ada)
    private volatile long deadline;
    private volatile long readUntil;       // permintaan baca SSID

    static boolean isConnected() {
        return instance != null;
    }

    static HotspotAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        HotspotEngine.get(this).log("aksesibilitas tersambung — kontrol otomatis siap");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        HotspotEngine.get(this).log("aksesibilitas terputus");
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (want == null && readUntil == 0L) return;
        if (!TetherScreens.isTetherPackage(event.getPackageName())) return;
        try {
            if (want != null) attempt();
            else readTick();
        } catch (Throwable t) {
            // jangan pernah biarkan satu error membunuh proses app
            HotspotEngine.get(this).log("aksesibilitas error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
    }

    // -- kontrol sakelar -------------------------------------------------------------

    void requestToggle(boolean on) {
        if (instance == null) return;
        want = Boolean.valueOf(on);
        deadline = System.currentTimeMillis() + 20000L;
        readUntil = 0L;
        HotspotEngine.get(this).log("automasi: minta " + (on ? "NYALA" : "MATI"));
        TetherScreens.open(this);
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, 700L);
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (want == null) return;
            try {
                if (System.currentTimeMillis() > deadline) {
                    finish(false, "timeout: sakelar hotspot tidak ditemukan");
                    return;
                }
                attempt();
            } catch (Throwable t) {
                finish(false, "error: " + t.getClass().getSimpleName());
                return;
            }
            if (want != null) handler.postDelayed(this, 600L);
        }
    };

    private void attempt() {
        Boolean target = want;
        if (target == null) return;
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable t) {
            return;
        }
        harvestSsid(root);
        TetherScreens.Outcome outcome = TetherScreens.act(root, target.booleanValue());
        if (outcome.kind == TetherScreens.Outcome.Kind.DONE) {
            finish(true, outcome.note);
        }
    }

    private void finish(boolean ok, String note) {
        boolean wasOn = want != null && want.booleanValue();
        want = null;
        handler.removeCallbacks(poll);
        HotspotEngine engine = HotspotEngine.get(this);
        engine.log((ok ? "✓ " : "✗ ") + "automasi: " + note);
        if (ok) {
            Prefs.setDesiredOn(this, wasOn);
            Prefs.setSystemOn(this, wasOn);
            Prefs.setLastBackend(this, "accessibility");
            engine.invalidate();
            goBackSoon();
        }
    }

    // -- baca nama WiFi --------------------------------------------------------------

    /** Minta baca SSID asli dari layar Setelan (buka lalu kembali). */
    void requestRead() {
        if (instance == null) return;
        want = null;
        readUntil = System.currentTimeMillis() + 8000L;
        HotspotEngine.get(this).log("automasi: baca nama WiFi dari sistem");
        TetherScreens.open(this);
        handler.removeCallbacks(readPoll);
        handler.postDelayed(readPoll, 700L);
    }

    private final Runnable readPoll = new Runnable() {
        public void run() {
            if (readUntil == 0L) return;
            try {
                readTick();
            } catch (Throwable t) {
                readUntil = 0L;
                handler.removeCallbacks(this);
                HotspotEngine.get(HotspotAccessibilityService.this)
                        .log("aksesibilitas error: " + t.getClass().getSimpleName());
                return;
            }
            if (readUntil != 0L) handler.postDelayed(this, 600L);
        }
    };

    private void readTick() {
        if (System.currentTimeMillis() > readUntil) {
            readUntil = 0L;
            handler.removeCallbacks(readPoll);
            HotspotEngine.get(this).log("✗ automasi: nama WiFi tidak ditemukan di layar");
            goBackSoon();
            return;
        }
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable t) {
            return;
        }
        if (harvestSsid(root)) {
            readUntil = 0L;
            handler.removeCallbacks(readPoll);
            goBackSoon();
        }
    }

    /** Simpan SSID/state kalau ketemu & berubah. true kalau ada yang disimpan. */
    private boolean harvestSsid(AccessibilityNodeInfo root) {
        boolean changed = false;
        HotspotEngine engine = HotspotEngine.get(this);

        String ssid = TetherScreens.readSsid(root);
        if (ssid != null && !ssid.isEmpty() && !ssid.equals(Prefs.systemSsid(this))) {
            Prefs.setSystemSsid(this, ssid);
            engine.log("✓ nama WiFi dibaca: \"" + ssid + "\"");
            changed = true;
        }

        Boolean on = TetherScreens.readSwitchState(root);
        if (on != null && (on.booleanValue() != Prefs.systemOn(this) || Prefs.systemOnAt(this) == 0L)) {
            Prefs.setSystemOn(this, on.booleanValue());
            Prefs.setDesiredOn(this, on.booleanValue());
            engine.log("✓ state hotspot dari sistem: " + (on ? "AKTIF" : "MATI"));
            changed = true;
        }

        if (changed) engine.invalidate();
        return changed;
    }

    private void goBackSoon() {
        handler.postDelayed(new Runnable() {
            public void run() {
                try {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                } catch (Throwable ignored) {
                }
            }
        }, 700L);
    }
}
