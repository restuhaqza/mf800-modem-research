package com.mifi.hotspot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Autostart hotspot + service saat boot (opsional, diaktifkan dari UI). */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.autostart(context)) return;
        HotspotEngine engine = HotspotEngine.get(context);
        engine.log("boot: autostart aktif");
        try {
            Intent svc = new Intent(context, HotspotService.class);
            svc.setAction(HotspotService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
            else context.startService(svc);
        } catch (Throwable t) {
            engine.log("boot: gagal start service — " + t.getMessage());
        }
    }
}
