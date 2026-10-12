package com.mifi.hotspot;

import android.annotation.TargetApi;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tile di Quick Settings (Android 7+): sekali tap nyala/mati hotspot. */
@TargetApi(24)
public final class HotspotTileService extends TileService {

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshAsync();
    }

    @Override
    public void onClick() {
        super.onClick();
        final HotspotEngine engine = HotspotEngine.get(this);
        final boolean target = !engine.status().enabled;
        engine.log("tile: " + (target ? "NYALAKAN" : "MATIKAN"));
        new Thread(new Runnable() {
            public void run() {
                if (target) engine.start();
                else engine.stop();
                engine.invalidate();
                refreshAsync();
            }
        }, "tile-toggle").start();
    }

    private void refreshAsync() {
        new Thread(new Runnable() {
            public void run() {
                final boolean on = HotspotEngine.get(HotspotTileService.this).status().enabled;
                ui.post(new Runnable() {
                    public void run() {
                        apply(on);
                    }
                });
            }
        }, "tile-refresh").start();
    }

    private void apply(boolean on) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        tile.updateTile();
    }
}
