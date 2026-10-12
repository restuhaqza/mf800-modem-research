package com.mifi.hotspot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Pengetahuan tentang layar tethering, hasil observasi langsung (dump UI) di:
 *   Sony SOV36 (Xperia XZ1), Android 9  ->  com.sonymobile.wifi/.hotspot.WifiApSettings
 *   switch  : com.sonymobile.wifi:id/switch_bar_switch   (checkable, tanpa dialog konfirmasi)
 *   catatan : android.settings.WIFI_TETHERING_SETTINGS TIDAK resolve di ROM ini,
 *             jadi harus dijalankan lewat component name.
 *
 * Jebakan yang dihindari: di layar hotspot Sony juga ada android:id/switch_widget
 * milik preferensi lain ("Make network name discoverable"), jadi id generik itu
 * hanya dipakai kalau tidak ada pilihan lain.
 */
final class TetherScreens {

    /** Paket yang event-nya kita dengar (dibatasi juga di accessibility_config.xml). */
    static final String[] EVENT_PACKAGES = {
            "com.sonymobile.wifi", "com.android.settings", "com.sonymobile.settings"
    };

    private static final String SONY_SWITCH_ID = "com.sonymobile.wifi:id/switch_bar_switch";

    private static final String[][] OPEN_COMPONENTS = {
            {"com.sonymobile.wifi", "com.sonymobile.wifi.hotspot.WifiApSettings"},
            {"com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"},
    };

    private static final String[] HOTSPOT_KEYWORDS = {
            "portable hotspot", "hotspot portabel", "hotspot wi-fi", "hotspot wifi", "hotspot"
    };

    private static final String[] DIALOG_BUTTONS = {
            "turn on", "nyalakan", "ok", "yes", "ya", "allow", "izinkan", "continue", "lanjutkan"
    };

    private TetherScreens() {}

    // -- membuka layar ---------------------------------------------------------------

    /** Buka layar tethering. Urutan: component device-specific dulu, baru action generik. */
    static boolean open(Context ctx) {
        for (String[] c : OPEN_COMPONENTS) {
            if (startComponent(ctx, c[0], c[1])) return true;
        }
        if (startAction(ctx, "android.settings.WIFI_TETHERING_SETTINGS")) return true;
        if (startAction(ctx, Settings.ACTION_WIRELESS_SETTINGS)) return true;
        return false;
    }

    private static boolean startComponent(Context ctx, String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean startAction(Context ctx, String action) {
        try {
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isTetherPackage(CharSequence pkg) {
        if (pkg == null) return false;
        String p = pkg.toString();
        for (String allowed : EVENT_PACKAGES) {
            if (p.equals(allowed)) return true;
        }
        return false;
    }

    // -- menggerakkan sakelar --------------------------------------------------------

    static final class Outcome {
        enum Kind { DONE, NAVIGATED, NONE }

        final Kind kind;
        final String note;

        private Outcome(Kind k, String n) { kind = k; note = n; }

        static Outcome done(String n) { return new Outcome(Kind.DONE, n); }

        static Outcome navigated() { return new Outcome(Kind.NAVIGATED, ""); }

        static Outcome none() { return new Outcome(Kind.NONE, ""); }
    }

    /**
     * Satu percobaan pada pohon UI saat ini.
     * @param want true = ingin hotspot menyala.
     */
    static Outcome act(AccessibilityNodeInfo root, boolean want) {
        if (root == null) return Outcome.none();

        // 1) dialog konfirmasi (beberapa ROM/SIM menampilkannya)
        Outcome dialog = handleDialog(root, want);
        if (dialog != null) return dialog;

        // 2) cari sakelar hotspot
        AccessibilityNodeInfo sw = findHotspotSwitch(root);
        if (sw != null) {
            boolean checked = sw.isChecked();
            if (checked == want) {
                return Outcome.done(want ? "hotspot sudah menyala" : "hotspot sudah mati");
            }
            if (click(sw)) {
                return Outcome.done(want ? "hotspot dinyalakan" : "hotspot dimatikan");
            }
        }

        // 3) layar tethering tanpa sakelar (mis. AOSP) -> klik baris "Portable hotspot"
        if (navigateToHotspot(root)) return Outcome.navigated();

        return Outcome.none();
    }

    private static AccessibilityNodeInfo findHotspotSwitch(AccessibilityNodeInfo root) {
        if (root == null) return null;
        // layar hotspot Sony: id pasti, dan satu-satunya sakelar di sana
        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (pkg.contains("sonymobile")) {
            AccessibilityNodeInfo sony = firstCheckableById(root, SONY_SWITCH_ID);
            if (sony != null) return sony;
        }

        // generik: ikat ke baris yang berlabel hotspot
        for (String kw : HOTSPOT_KEYWORDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                String label = labelOf(n);
                if (label.contains("usb") || label.contains("bluetooth")) continue;
                AccessibilityNodeInfo sw = switchNear(n);
                if (sw != null) return sw;
            }
        }
        return null;
    }

    /** Cari sakelar di sekitar node berlabel (naik beberapa level, cek id lalu kelas Switch). */
    private static AccessibilityNodeInfo switchNear(AccessibilityNodeInfo from) {
        AccessibilityNodeInfo cur = from;
        for (int depth = 0; depth < 6 && cur != null; depth++) {
            AccessibilityNodeInfo byId = firstCheckableById(cur, "android:id/switch_widget");
            if (byId != null) return byId;
            AccessibilityNodeInfo byClass = firstSwitchDescendant(cur, 0);
            if (byClass != null) return byClass;
            cur = cur.getParent();
        }
        return null;
    }

    private static AccessibilityNodeInfo firstCheckableById(AccessibilityNodeInfo root, String id) {
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(id);
            if (list != null) {
                for (AccessibilityNodeInfo n : list) {
                    if (n.isCheckable()) return n;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static AccessibilityNodeInfo firstSwitchDescendant(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 6) return null;
        String cls = node.getClassName() == null ? "" : node.getClassName().toString();
        if (node.isCheckable() && cls.contains("Switch")) return node;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo res = firstSwitchDescendant(node.getChild(i), depth + 1);
            if (res != null) return res;
        }
        return null;
    }

    /** Di layar tethering AOSP, "Portable hotspot" hanya baris navigasi (tanpa sakelar). */
    private static boolean navigateToHotspot(AccessibilityNodeInfo root) {
        for (String kw : HOTSPOT_KEYWORDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                String label = labelOf(n);
                if (label.contains("usb") || label.contains("bluetooth")) continue;
                // jangan klik penjelasan panjang / judul layar
                if (n.isClickable() || hasClickableAncestor(n, 3)) {
                    return click(n);
                }
            }
        }
        return false;
    }

    private static boolean hasClickableAncestor(AccessibilityNodeInfo node, int maxUp) {
        AccessibilityNodeInfo cur = node.getParent();
        for (int i = 0; i < maxUp && cur != null; i++) {
            if (cur.isClickable()) return true;
            cur = cur.getParent();
        }
        return false;
    }

    /** Klik node, atau leluhur terdekat yang clickable. */
    private static boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            cur = cur.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /** Kembalikan Outcome kalau dialog ditangani, atau null kalau tidak ada dialog. */
    private static Outcome handleDialog(AccessibilityNodeInfo root, boolean want) {
        if (!want) return null; // untuk mematikan, dialog biasanya tidak muncul
        for (String kw : HOTSPOT_KEYWORDS) {
            List<AccessibilityNodeInfo> marker = root.findAccessibilityNodeInfosByText(kw);
            if (marker == null || marker.isEmpty()) continue;
            for (String btn : DIALOG_BUTTONS) {
                List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByText(btn);
                if (buttons == null) continue;
                for (AccessibilityNodeInfo b : buttons) {
                    String t = labelOf(b);
                    if (!t.equals(btn) && !t.startsWith(btn)) continue;
                    if (click(b)) return Outcome.navigated(); // tunggu layar berubah, cek lagi
                }
            }
        }
        return null;
    }

    private static String labelOf(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        if (node.getText() != null) sb.append(node.getText());
        if (node.getContentDescription() != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(node.getContentDescription());
        }
        return sb.toString().toLowerCase();
    }

    // -- membaca nama WiFi (SSID) ----------------------------------------------------

    private static final String[] SSID_LABELS = {"network name", "nama jaringan"};

    /**
     * Ambil SSID asli dari layar hotspot, mis. teks:
     *   "Network name: KodeSpace Labs\nSecurity: WPA2 PSK"
     * Tidak bisa dibaca lewat API tanpa root (getWifiApConfiguration dijaga
     * NETWORK_SETTINGS), jadi ini satu-satunya sumber tanpa root.
     */
    static String readSsid(AccessibilityNodeInfo root) {
        if (root == null) return null;
        for (String label : SSID_LABELS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                String text = plainText(n);
                String lower = text.toLowerCase();
                // harus diawali label ("Make network name discoverable" ditolak)
                if (!lower.startsWith(label)) continue;
                String rest = text.substring(label.length());
                rest = rest.replaceFirst("^\\s*[:：]\\s*", "");
                int cut = rest.indexOf('\n');
                if (cut < 0) cut = rest.indexOf('\r');
                if (cut >= 0) rest = rest.substring(0, cut);
                rest = rest.trim();
                if (!rest.isEmpty() && rest.length() <= 32) return rest;
            }
        }
        return null;
    }

    private static String plainText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        if (node.getText() != null) sb.append(node.getText());
        if (node.getContentDescription() != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(node.getContentDescription());
        }
        return sb.toString();
    }

    /**
     * Baca state sakelar hotspot dari layar yang sedang terbuka (null kalau tidak ada).
     * Berguna karena tanpa root state tidak bisa dibaca lewat API.
     */
    static Boolean readSwitchState(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo sw = findHotspotSwitch(root);
        if (sw == null) return null;
        return Boolean.valueOf(sw.isChecked());
    }
}
