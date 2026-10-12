package com.mifi.hotspot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground service: menjaga proses hidup + menjalankan HTTP API. */
public final class HotspotService extends Service {

    static final String ACTION_START = "com.mifi.hotspot.START";
    static final String ACTION_STOP = "com.mifi.hotspot.STOP";
    static final String ACTION_RESTART_API = "com.mifi.hotspot.RESTART_API";
    static final String EXTRA_PORT = "port";

    private static final String CHANNEL = "hotspot";
    private static final int NOTIF_ID = 42;

    private static volatile HotspotService instance;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private HotspotEngine engine;
    private ApiServer api;

    static ApiServer api() {
        HotspotService s = instance;
        return s == null ? null : s.api;
    }

    static boolean running() {
        return instance != null;
    }

    static void restartApi(int port) {
        HotspotService s = instance;
        if (s == null) return;
        final int p = port;
        s.io.execute(new Runnable() {
            public void run() {
                s.api.start(p);
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        engine = HotspotEngine.get(this);
        startForegroundNotice();
        api = new ApiServer(this);
        io.execute(new Runnable() {
            public void run() {
                api.start(Prefs.port(HotspotService.this));
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            io.execute(new Runnable() {
                public void run() {
                    engine.start();
                }
            });
        } else if (ACTION_STOP.equals(action)) {
            io.execute(new Runnable() {
                public void run() {
                    engine.stop();
                }
            });
        } else if (ACTION_RESTART_API.equals(action)) {
            final int port = intent.getIntExtra(EXTRA_PORT, Prefs.port(this));
            io.execute(new Runnable() {
                public void run() {
                    api.start(port);
                }
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (api != null) api.stop();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // -- notifikasi ------------------------------------------------------------------

    private void startForegroundNotice() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "Kontrol hotspot", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Menjaga API kontrol hotspot tetap hidup");
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(getString(R.string.app_name))
                .setContentText("Kontrol web aktif di :" + Prefs.port(this))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true);
        try {
            startForeground(NOTIF_ID, b.build());
        } catch (Throwable t) {
            // Android 14+ mewajibkan foregroundServiceType (atribut belum ada di SDK 28).
            // Service tetap jalan selama app di depan; jangan sampai app crash.
            engine.log("peringatan: startForeground ditolak (" + t.getClass().getSimpleName() + ")");
        }
    }
}
