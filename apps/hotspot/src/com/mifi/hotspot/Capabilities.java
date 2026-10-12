package com.mifi.hotspot;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

/** Ringkasan kapabilitas device — ditampilkan di UI & endpoint /api/capabilities. */
final class Capabilities {

    final String manufacturer;
    final String model;
    final String release;
    final int sdk;
    final boolean api26;
    final boolean root;
    final boolean tetherPermission;
    final String rootError;

    Capabilities(Context c) {
        manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        model = Build.MODEL == null ? "" : Build.MODEL;
        release = Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE;
        sdk = Build.VERSION.SDK_INT;
        api26 = sdk >= 26;
        root = Shell.hasRoot();
        rootError = Shell.rootError();
        tetherPermission = c.checkPermission(
                "android.permission.TETHER_PRIVILEGED",
                Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
    }

    String device() {
        String m = (manufacturer + " " + model).trim();
        return m.isEmpty() ? "Android" : m;
    }

    /** Backend yang paling mungkin berhasil. */
    String preferredBackend() {
        if (api26 && tetherPermission) return "privileged";
        if (root) return "root-app_process";
        return "settings";
    }

    String hint() {
        if (api26 && tetherPermission) {
            return "App punya TETHER_PRIVILEGED — kontrol hotspot penuh lewat API resmi.";
        }
        if (root) {
            return "Root terdeteksi — kontrol hotspot lewat app_process (uid 0).";
        }
        return "Tanpa root: aktifkan layanan aksesibilitas agar app bisa mengklik sakelar hotspot "
                + "di Setelan secara otomatis. Tanpa itu, app hanya membuka layar Setelan. "
                + (rootError.isEmpty() ? "" : "(" + rootError + ")");
    }
}
