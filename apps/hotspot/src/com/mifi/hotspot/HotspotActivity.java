package com.mifi.hotspot;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UI native: status, toggle hotspot, konfigurasi, daftar client, log. */
public final class HotspotActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HotspotEngine engine;

    private TextView subtitle, stateBadge, stateInfo, tvWeb, tvClients, tvLog, tvA11y;
    private Button btnToggle, btnSettings, btnApply, btnRefresh, btnA11y, btnReadSsid;
    private EditText etSsid, etPass, etPin, etPort;
    private RadioButton bandAuto, band24, band5;
    private CheckBox cbAutostart;

    private volatile boolean busy = false;
    private volatile boolean lastEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        engine = HotspotEngine.get(this);

        subtitle = (TextView) findViewById(R.id.subtitle);
        stateBadge = (TextView) findViewById(R.id.stateBadge);
        stateInfo = (TextView) findViewById(R.id.stateInfo);
        tvWeb = (TextView) findViewById(R.id.tvWeb);
        tvClients = (TextView) findViewById(R.id.tvClients);
        tvLog = (TextView) findViewById(R.id.tvLog);
        tvA11y = (TextView) findViewById(R.id.tvA11y);

        btnToggle = (Button) findViewById(R.id.btnToggle);
        btnSettings = (Button) findViewById(R.id.btnSettings);
        btnApply = (Button) findViewById(R.id.btnApply);
        btnRefresh = (Button) findViewById(R.id.btnRefresh);
        btnA11y = (Button) findViewById(R.id.btnA11y);
        btnReadSsid = (Button) findViewById(R.id.btnReadSsid);

        etSsid = (EditText) findViewById(R.id.etSsid);
        etPass = (EditText) findViewById(R.id.etPass);
        etPin = (EditText) findViewById(R.id.etPin);
        etPort = (EditText) findViewById(R.id.etPort);

        bandAuto = (RadioButton) findViewById(R.id.bandAuto);
        band24 = (RadioButton) findViewById(R.id.band24);
        band5 = (RadioButton) findViewById(R.id.band5);
        cbAutostart = (CheckBox) findViewById(R.id.cbAutostart);

        loadPrefsIntoFields();

        btnToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggle();
            }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                engine.openSettings();
            }
        });
        btnApply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                applyConfig();
            }
        });
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                engine.invalidate();
                refresh(true);
            }
        });
        btnA11y.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                engine.log("buka Setelan aksesibilitas");
                engine.openAccessibilitySettings();
            }
        });
        btnReadSsid.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!engine.accessibilityConnected()) {
                    toast("Aktifkan layanan aksesibilitas dulu");
                    return;
                }
                toast("Membaca nama WiFi dari HP…");
                io.execute(new Runnable() {
                    public void run() {
                        engine.readSystemSsid();
                    }
                });
            }
        });

        startService();
        engine.log("app dibuka");
    }

    @Override
    protected void onResume() {
        super.onResume();
        engine.invalidate();
        refresh(true);
        ui.removeCallbacks(poller);
        ui.postDelayed(poller, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poller);
    }

    private final Runnable poller = new Runnable() {
        public void run() {
            refresh(false);
            ui.postDelayed(this, 3000);
        }
    };

    private void startService() {
        try {
            Intent i = new Intent(this, HotspotService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            engine.log("gagal start service: " + t.getMessage());
        }
    }

    // -- aksi ------------------------------------------------------------------------

    private void toggle() {
        if (busy) return;
        busy = true;
        btnToggle.setEnabled(false);
        btnToggle.setText("bekerja…");
        final boolean target = !lastEnabled;
        engine.log(target ? "tombol: NYALAKAN" : "tombol: MATIKAN");
        io.execute(new Runnable() {
            public void run() {
                try {
                    if (target) engine.start();
                    else engine.stop();
                } catch (Throwable t) {
                    engine.log("error: " + t.getMessage());
                }
                engine.invalidate();
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        btnToggle.setEnabled(true);
                        refresh(true);
                    }
                });
            }
        });
    }

    private void applyConfig() {
        final String ssid = etSsid.getText().toString().trim();
        final String pass = etPass.getText().toString();
        final String pin = etPin.getText().toString().trim();
        int parsedPort;
        try {
            parsedPort = Integer.parseInt(etPort.getText().toString().trim());
        } catch (Throwable t) {
            parsedPort = Prefs.DEFAULT_PORT;
            etPort.setText(String.valueOf(parsedPort));
        }
        final int port = parsedPort;
        final String band = band24.isChecked() ? "2.4" : band5.isChecked() ? "5" : "auto";
        final boolean autostart = cbAutostart.isChecked();

        String warn = TetherGuard.warn(this, ssid, pass, pin);
        if (warn != null) {
            toast(warn);
            engine.log("tolak simpan: " + warn);
            return;
        }
        engine.log("simpan konfigurasi (ssid=" + ssid + ", port=" + port + ")");
        io.execute(new Runnable() {
            public void run() {
                engine.saveConfig(new Cfg(ssid, pass, band), pin, port, autostart);
                ApiServer current = HotspotService.api();
                if (current == null || current.port() != port) {
                    HotspotService.restartApi(port);
                }
                engine.invalidate();
                ui.post(new Runnable() {
                    public void run() {
                        toast("konfigurasi disimpan");
                        refresh(true);
                    }
                });
            }
        });
    }

    // -- refresh ---------------------------------------------------------------------

    private void refresh(final boolean full) {
        io.execute(new Runnable() {
            public void run() {
                final HotspotEngine.Status s = engine.status();
                final Capabilities caps = engine.caps();
                final String logText = full ? engine.logText() : null;
                final int port = Prefs.port(HotspotActivity.this);
                final String url = urlFor(port);
                final String hotspotUrl = TetherGuard.hotspotUrl(s.clients, port);
                final boolean a11yEnabled = engine.accessibilityEnabled();
                final boolean a11yConnected = engine.accessibilityConnected();
                ui.post(new Runnable() {
                    public void run() {
                        render(s, caps, port, url, hotspotUrl, logText, a11yEnabled, a11yConnected);
                    }
                });
            }
        });
    }

    private void render(HotspotEngine.Status s, Capabilities caps, int port, String url,
                        String hotspotUrl, String logText, boolean a11yEnabled, boolean a11yConnected) {
        lastEnabled = s.enabled;

        stateBadge.setText(s.enabled ? "● HOTSPOT AKTIF" : "○ HOTSPOT NONAKTIF");
        stateBadge.setTextColor(s.enabled ? 0xFF8DC63F : 0xFFB9C4B4);

        StringBuilder sb = new StringBuilder();
        sb.append("device   : ").append(caps.device()).append('\n');
        sb.append("android  : ").append(caps.release).append(" (API ").append(caps.sdk).append(")\n");
        sb.append("root     : ").append(caps.root ? "ya" : "tidak").append('\n');
        sb.append("privileged: ").append(caps.tetherPermission ? "ya" : "tidak").append('\n');
        sb.append("ssid     : ").append(s.ssid.isEmpty() ? "-" : s.ssid)
                .append(s.ssidSystem.isEmpty() ? "   (perkiraan app — tekan \"Baca nama WiFi\")" : "   (dibaca dari sistem)")
                .append('\n');
        if (!s.ssidSystem.isEmpty() && !s.ssidApp.isEmpty() && !s.ssidSystem.equals(s.ssidApp)) {
            sb.append("ssid app : ").append(s.ssidApp).append('\n');
        }
        sb.append("backend  : ").append(s.backend).append('\n');
        sb.append("verifikasi: ").append(s.verified ? "langsung dari sistem" : "perkiraan (butuh root)");
        if (!s.error.isEmpty()) sb.append("\ncatatan  : ").append(s.error);
        sb.append('\n').append(caps.hint());
        stateInfo.setText(sb.toString());

        subtitle.setText(caps.device() + " · " + caps.release);
        btnToggle.setText(s.enabled ? getString(R.string.stop) : getString(R.string.start));

        if (a11yConnected) {
            tvA11y.setText(getString(R.string.a11y_status_on));
        } else if (a11yEnabled) {
            tvA11y.setText("Dicentang, tapi layanan belum tersambung — coba matikan & nyalakan lagi.");
        } else {
            tvA11y.setText(getString(R.string.a11y_status_off));
        }

        StringBuilder web = new StringBuilder();
        web.append("di HP ini       : http://127.0.0.1:").append(port).append("/\n");
        if (hotspotUrl != null) {
            web.append("client hotspot  : ").append(hotspotUrl).append('\n');
        } else if (s.enabled) {
            web.append("client hotspot  : http://192.168.43.1:").append(port)
                    .append("/ (atau gateway yang didapat client)\n");
        }
        web.append("WiFi sama / USB : ").append(url).append('\n');
        web.append("PIN             : ").append(Prefs.pin(this));
        tvWeb.setText(web.toString());

        if (s.clients.isEmpty()) {
            tvClients.setText("belum ada perangkat");
        } else {
            StringBuilder c = new StringBuilder();
            for (Client cl : s.clients) {
                c.append(cl.ip).append('\n');
                c.append("   ").append(cl.mac)
                        .append(cl.name.isEmpty() ? "" : "  " + cl.name)
                        .append("  [").append(cl.source).append("]\n");
            }
            tvClients.setText(c.toString().trim());
        }

        if (logText != null) tvLog.setText(logText.isEmpty() ? "-" : logText);
    }

    private String urlFor(int port) {
        String ip = TetherGuard.localIp(this);
        return "http://" + (ip == null ? "<ip-hotspot>" : ip) + ":" + port + "/";
    }

    private void loadPrefsIntoFields() {
        SharedPreferences sp = Prefs.sp(this);
        etSsid.setText(Prefs.ssid(this));
        etPass.setText(Prefs.pass(this));
        etPin.setText(Prefs.pin(this));
        etPort.setText(String.valueOf(Prefs.port(this)));
        cbAutostart.setChecked(Prefs.autostart(this));
        String band = Prefs.band(this);
        if ("2.4".equals(band)) band24.setChecked(true);
        else if ("5".equals(band)) band5.setChecked(true);
        else bandAuto.setChecked(true);
        if (!sp.contains("ssid")) engine.log("catatan: SSID default " + Prefs.defaultSsid());
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }
}
