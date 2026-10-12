package com.mifi.hotspot;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point yang dijalankan `app_process` SEBAGAI ROOT (uid 0).
 *
 * Proses root lolos cek permission NETWORK_SETTINGS/TETHER_PRIVILEGED, jadi di
 * sinilah setWifiApEnabled() benar-benar dieksekusi. Output = satu baris JSON di
 * stdout, lalu proses langsung halt() supaya tidak menggantung.
 */
public final class TetherCli {

    private static Context ctx;
    private static WifiManager wm;

    public static void main(String[] args) {
        Looper.prepareMainLooper();   // ActivityThread.systemMain() butuh Looper
        exemptHiddenApis();
        try {
            ctx = systemContext();
            wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            dispatch(args.length > 0 ? args[0] : "status", args);
        } catch (Throwable t) {
            emit("{\"ok\":false,\"error\":\"" + esc(describe(t)) + "\"}", 1);
        }
    }

    private static void dispatch(String action, String[] args) throws Throwable {
        if ("status".equals(action)) {
            emit(statusJson(), 0);
            return;
        }
        if ("setconfig".equals(action)) {
            if (args.length < 3) {
                emit("{\"ok\":false,\"error\":\"butuh ssid & password\"}", 2);
                return;
            }
            ApApi.setConfig(wm, ApApi.buildConfig(args[1], args[2], bandCode(args)));
            emit("{\"ok\":true}", 0);
            return;
        }
        if ("start".equals(action)) {
            WifiConfiguration cfg;
            if (args.length >= 3) {
                cfg = ApApi.buildConfig(args[1], args[2], bandCode(args));
                try {
                    ApApi.setConfig(wm, cfg);
                } catch (Throwable ignored) {
                    // konfigurasi opsional
                }
            } else {
                cfg = ApApi.getConfig(wm);
            }
            if (cfg == null) cfg = ApApi.buildConfig(Prefs.defaultSsid(), "12345678", 0);

            boolean accepted = ApApi.setEnabled(wm, cfg, true);
            boolean on = ApApi.isEnabled(wm);
            if (accepted || on) {
                emit("{\"ok\":true,\"enabled\":" + on + "}", 0);
            } else {
                emit("{\"ok\":false,\"error\":\"setWifiApEnabled() menolak permintaan\"}", 4);
            }
            return;
        }
        if ("stop".equals(action)) {
            ApApi.setEnabled(wm, null, false);
            emit("{\"ok\":true,\"enabled\":" + ApApi.isEnabled(wm) + "}", 0);
            return;
        }
        emit("{\"ok\":false,\"error\":\"aksi tidak dikenal: " + esc(action) + "\"}", 2);
    }

    private static int bandCode(String[] args) {
        if (args.length > 3) {
            if ("2.4".equals(args[3])) return 1;
            if ("5".equals(args[3])) return 2;
        }
        return 0;
    }

    // -- status ---------------------------------------------------------------------

    private static String statusJson() {
        boolean enabled = ApApi.isEnabled(wm);
        String ssid = "";
        try {
            WifiConfiguration c = ApApi.getConfig(wm);
            if (c != null && c.SSID != null) ssid = c.SSID;
        } catch (Throwable ignored) {
        }
        List<String[]> clients = clients();
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"ok\":true,\"enabled\":").append(enabled)
                .append(",\"ssid\":\"").append(esc(ssid)).append('"')
                .append(",\"clients\":[");
        for (int i = 0; enabled && i < clients.size(); i++) {
            String[] c = clients.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"ip\":\"").append(esc(c[0]))
                    .append("\",\"mac\":\"").append(esc(c[1]))
                    .append("\",\"name\":\"").append(esc(c[2]))
                    .append("\",\"source\":\"").append(esc(c[3]))
                    .append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Kumpulkan client hotspot dari dnsmasq.leases + /proc/net/arp. */
    private static List<String[]> clients() {
        Map<String, String[]> byIp = new LinkedHashMap<String, String[]>();
        Map<String, String> macToName = new LinkedHashMap<String, String>();

        try {
            BufferedReader r = new BufferedReader(new FileReader("/data/misc/dhcp/dnsmasq.leases"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 4 && Client.looksLikeMac(f[1])) {
                    String name = "*".equals(f[3]) ? "" : f[3];
                    byIp.put(f[2], new String[]{f[2], f[1], name, "lease"});
                    if (!name.isEmpty()) macToName.put(f[1].toLowerCase(), name);
                }
            }
            r.close();
        } catch (Throwable ignored) {
        }

        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"));
            String line;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] f = line.trim().split("\\s+");
                if (f.length < 6) continue;
                if (!Client.looksLikeMac(f[3]) || "00:00:00:00:00:00".equals(f[3])) continue;
                if (!Client.isApInterface(f[5])) continue;
                if (!byIp.containsKey(f[0])) {
                    String name = macToName.get(f[3].toLowerCase());
                    byIp.put(f[0], new String[]{f[0], f[3], name == null ? "" : name, "arp"});
                }
            }
            r.close();
        } catch (Throwable ignored) {
        }

        return new ArrayList<String[]>(byIp.values());
    }

    // -- util -----------------------------------------------------------------------

    private static void exemptHiddenApis() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object rt = vm.getMethod("getRuntime").invoke(null);
            Method setExemptions = vm.getMethod("setHiddenApiExemptions", String[].class);
            setExemptions.invoke(rt, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        return (Context) at.getMethod("getSystemContext").invoke(thread);
    }

    private static String describe(Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        String msg = cause.getMessage();
        return cause.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static void emit(String json, int code) {
        System.out.println(json);
        System.out.flush();
        try { Thread.sleep(80); } catch (InterruptedException ignored) {}
        Runtime.getRuntime().halt(code);
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(' ');
                    else b.append(ch);
            }
        }
        return b.toString();
    }

    private TetherCli() {
    }
}
