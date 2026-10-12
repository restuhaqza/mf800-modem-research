package com.mifi.helper;

import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public class HttpApiService extends Service {

    public static final int PORT = 8080;
    public static final java.util.concurrent.atomic.AtomicReference<String> lastUssd =
            new java.util.concurrent.atomic.AtomicReference<String>(null);

    private static final String DEFAULT_PIN = "admin";
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 5 * 60 * 1000L;

    private Thread serverThread;
    private ServerSocket server;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startServer();
        return START_STICKY;
    }

    private synchronized void startServer() {
        if (serverThread != null && serverThread.isAlive()) return;
        serverThread = new Thread(new Runnable() {
            public void run() {
                serve();
            }
        });
        serverThread.start();
    }

    private void serve() {
        // Make the many missing toolbox commands (awk/sed/head/tail/find/free/...)
        // available to the terminal by exposing BusyBox applets in the app dir.
        installBusybox();
        try {
            server = new ServerSocket(PORT, 64, InetAddress.getByName("0.0.0.0"));
        } catch (Exception e) {
            android.util.Log.e("MifiHelper", "cannot bind " + PORT, e);
            return;
        }
        android.util.Log.i("MifiHelper", "HTTP API listening on " + PORT);
        while (true) {
            try {
                final Socket s = server.accept();
                new Thread(new Runnable() {
                    public void run() {
                        handle(s);
                    }
                }).start();
            } catch (Exception e) {
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String reqLine = in.readLine();
            if (reqLine == null) { s.close(); return; }
            int contentLength = 0;
            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while ((line = in.readLine()) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
                }
                String l = line.toLowerCase();
                if (l.startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception e) {}
                }
            }
            char[] buf = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int r = in.read(buf, read, contentLength - read);
                if (r < 0) break;
                read += r;
            }
            String body = new String(buf, 0, read);
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            if (path.startsWith("/api/pty")
                    && "websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                handlePtyWs(s, path, headers);
                return;
            }
            if ("OPTIONS".equals(method)) {
                respond(s, 200, "{\"ok\":true}");
                return;
            }
            String[] out = route(path, body, headers);
            respond(s, Integer.parseInt(out[0]), out[1]);
        } catch (Exception e) {
            try { s.close(); } catch (Exception x) {}
        }
    }

    /* ------------------------------------------------------------------ *
     * Interactive PTY terminal over WebSocket (RFC 6455), no TTY needed   *
     * ------------------------------------------------------------------ */

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static int intOr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Upgrade a GET /api/pty request to a WebSocket and bridge it to a real PTY.
     * Auth is via ?pin= (browsers cannot set custom headers on a WebSocket).
     * Binary frames are raw keystrokes/terminal output; text frames are JSON
     * control messages (currently only {"resize":[cols,rows]}).
     */
    private void handlePtyWs(Socket s, String fullPath, Map<String, String> headers) {
        try {
            String key = headers.get("sec-websocket-key");
            int qm = fullPath.indexOf('?');
            Map<String, String> q = parseQuery(qm >= 0 ? fullPath.substring(qm + 1) : "");
            if (key == null) { respond(s, 400, jsonErr("missing Sec-WebSocket-Key")); return; }
            if (!Pty.available()) { respond(s, 503, jsonErr("pty unavailable on this device")); return; }
            if (!checkPin(q.get("pin"))) { respond(s, 401, jsonErr("unauthorized")); return; }

            int cols = clamp(intOr(q.get("cols"), 100), 20, 500);
            int rows = clamp(intOr(q.get("rows"), 30), 5, 200);

            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((key + WS_GUID).getBytes("US-ASCII"));
            String accept = Base64.encodeToString(md.digest(), Base64.NO_WRAP);

            s.setSoTimeout(0);
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes("US-ASCII"));
            out.flush();

            File home = new File(getFilesDir(), "home");
            try { home.mkdirs(); } catch (Exception e) {}
            final int fd = Pty.nativeSpawn(cols, rows, SHELL, shellPath(), home.getAbsolutePath());
            if (fd < 0) {
                android.util.Log.w("MifiHelper", "pty spawn failed: " + fd);
                try { s.close(); } catch (Exception e) {}
                return;
            }

            final Ws ws = new Ws(s.getInputStream(), out);
            final Socket sock = s;
            Thread pump = new Thread(new Runnable() {
                public void run() {
                    try {
                        byte[] buf = new byte[8192];
                        while (true) {
                            int n = Pty.nativeRead(fd, buf);
                            if (n <= 0) break;
                            byte[] d = new byte[n];
                            System.arraycopy(buf, 0, d, 0, n);
                            ws.send(0x02, d);
                        }
                    } catch (Throwable t) {}
                    try { ws.send(0x08, new byte[0]); } catch (Throwable t) {}
                    try { sock.close(); } catch (Throwable t) {}
                }
            }, "pty-pump");
            pump.setDaemon(true);
            pump.start();

            try {
                while (true) {
                    Frame f = ws.read();
                    if (f == null) break;
                    if (f.op < 0) continue;          // incomplete fragment
                    if (f.op == 0x08) break;         // close
                    if (f.op == 0x09) { ws.send(0x0A, f.data); continue; } // ping -> pong
                    if (f.op == 0x0A) continue;      // pong
                    if (f.op == 0x01) {              // text = control JSON
                        try {
                            JSONObject o = new JSONObject(new String(f.data, "UTF-8"));
                            if (o.has("resize")) {
                                JSONArray a = o.getJSONArray("resize");
                                Pty.nativeResize(fd, a.getInt(0), a.getInt(1));
                            }
                        } catch (Exception e) {}
                        continue;
                    }
                    if (f.data != null && f.data.length > 0) Pty.writeAll(fd, f.data);
                }
            } catch (Throwable t) {}
            Pty.nativeClose(fd);
            try { s.close(); } catch (Exception e) {}
        } catch (Throwable t) {
            try { s.close(); } catch (Exception e) {}
        }
    }

    private static final class Frame {
        final int op;
        final byte[] data;
        Frame(int op, byte[] data) { this.op = op; this.data = data; }
    }

    /** Minimal WebSocket connection: masked client frames in, unmasked server frames out. */
    private static final class Ws {
        private final InputStream in;
        private final OutputStream out;
        private final Object lock = new Object();
        private final ByteArrayOutputStream cont = new ByteArrayOutputStream();
        private int contOp = -1;

        Ws(InputStream in, OutputStream out) { this.in = in; this.out = out; }

        void send(int op, byte[] data) throws Exception {
            synchronized (lock) {
                ByteArrayOutputStream h = new ByteArrayOutputStream();
                h.write(0x80 | (op & 0x0f));
                int len = data.length;
                if (len < 126) {
                    h.write(len);
                } else if (len <= 0xffff) {
                    h.write(126);
                    h.write((len >> 8) & 0xff);
                    h.write(len & 0xff);
                } else {
                    h.write(127);
                    for (int i = 7; i >= 0; i--) h.write((len >>> (8 * i)) & 0xff);
                }
                out.write(h.toByteArray());
                out.write(data);
                out.flush();
            }
        }

        Frame read() throws Exception {
            int b0 = in.read();
            if (b0 < 0) return null;
            int b1 = in.read();
            if (b1 < 0) return null;
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) {
                len = ((long) in.read() << 8) | in.read();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            if (len < 0 || len > (1 << 20)) throw new Exception("bad frame length " + len);
            byte[] mask = new byte[4];
            if (masked) readFully(mask);
            byte[] data = new byte[(int) len];
            readFully(data);
            if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];

            if (op == 0x00) {                       // continuation
                cont.write(data, 0, data.length);
                if (fin) {
                    byte[] all = cont.toByteArray();
                    int o = contOp;
                    cont.reset();
                    contOp = -1;
                    return new Frame(o, all);
                }
                return new Frame(-1, null);
            }
            if ((op == 0x01 || op == 0x02) && !fin) { // fragmented start
                cont.reset();
                cont.write(data, 0, data.length);
                contOp = op;
                return new Frame(-1, null);
            }
            return new Frame(op, data);
        }

        private void readFully(byte[] dst) throws Exception {
            int off = 0;
            while (off < dst.length) {
                int n = in.read(dst, off, dst.length - off);
                if (n < 0) throw new Exception("eof");
                off += n;
            }
        }
    }

    private void respond(Socket s, int code, String json) throws Exception {
        String reason = code == 200 ? "OK"
                : code == 401 ? "Unauthorized"
                : code == 429 ? "Too Many Requests"
                : code == 400 ? "Bad Request"
                : "OK";
        byte[] out = json.getBytes("UTF-8");
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + out.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: X-Auth, Content-Type\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        os.write(out);
        os.flush();
        s.close();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("auth", MODE_PRIVATE);
    }

    private boolean checkPin(String given) {
        if (given == null) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(
                    md.digest(given.getBytes("UTF-8")),
                    md.digest(prefs().getString("pin", DEFAULT_PIN).getBytes("UTF-8")));
        } catch (Exception e) {
            return false;
        }
    }

    private String jsonErr(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("error", msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + msg + "\"}";
        }
    }

    /** null = authorized; else [httpCode, json]. bodyPin = pin taken from body (auth endpoints). */
    private String[] authorize(Map<String, String> headers, String bodyPin) {
        long lockedUntil = prefs().getLong("locked_until", 0L);
        if (lockedUntil > System.currentTimeMillis()) {
            long secs = (lockedUntil - System.currentTimeMillis()) / 1000 + 1;
            return new String[]{"429", jsonErr("locked; retry after " + secs + "s")};
        }
        String given = headers.containsKey("x-auth") ? headers.get("x-auth") : bodyPin;
        if (given != null && checkPin(given)) {
            prefs().edit().putInt("fails", 0).commit();
            return null;
        }
        int fails = prefs().getInt("fails", 0) + 1;
        SharedPreferences.Editor ed = prefs().edit().putInt("fails", fails);
        String msg = "unauthorized";
        if (fails >= MAX_FAILS) {
            ed.putLong("locked_until", System.currentTimeMillis() + LOCK_MS).putInt("fails", 0);
            msg = "too many failures; locked for 5 minutes";
        }
        ed.commit();
        return new String[]{"401", jsonErr(msg)};
    }

    private Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<String, String>();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                try {
                    m.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                          URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                } catch (Exception e) {}
            }
        }
        return m;
    }

    private String[] route(String full, String body, Map<String, String> headers) {
        try {
            String path;
            Map<String, String> q;
            int qm = full.indexOf('?');
            if (qm >= 0) {
                path = full.substring(0, qm);
                q = parseQuery(full.substring(qm + 1));
            } else {
                path = full;
                q = new HashMap<String, String>();
            }
            Map<String, String> p = parseQuery(body);

            boolean open = path.equals("/api/status")
                    || path.equals("/api/battery")
                    || path.equals("/api/auth/login")
                    || path.equals("/api/auth/pin")
                    || !path.startsWith("/api/");
            if (!open) {
                String[] a = authorize(headers, null);
                if (a != null) return a;
            }

            if (path.equals("/api/auth/login")) {
                String pin = p.containsKey("pin") ? p.get("pin") : q.get("pin");
                String[] a = authorize(headers, pin);
                if (a != null) return a;
                JSONObject o = new JSONObject();
                o.put("ok", true);
                return new String[]{"200", o.toString()};
            }
            if (path.equals("/api/auth/pin")) {
                String oldPin = p.containsKey("old") ? p.get("old") : q.get("old");
                String next = p.containsKey("next") ? p.get("next") : q.get("next");
                String[] a = authorize(headers, oldPin);
                if (a != null) return a;
                if (next == null || next.length() < 4 || next.length() > 32) {
                    return new String[]{"400", jsonErr("pin must be 4-32 characters")};
                }
                prefs().edit().putString("pin", next).putInt("fails", 0).commit();
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("changed", true);
                return new String[]{"200", o.toString()};
            }
            if (path.equals("/api/sms/list")) {
                return new String[]{"200", smsList(q.containsKey("box") ? q.get("box") : "inbox",
                        q.containsKey("limit") ? q.get("limit") : "100")};
            }
            if (path.equals("/api/sms/get")) {
                return new String[]{"200", smsGet(q.get("id"), q.containsKey("box") ? q.get("box") : "inbox")};
            }
            if (path.equals("/api/sms/delete")) {
                String id = p.containsKey("id") ? p.get("id") : q.get("id");
                int n = getContentResolver().delete(Uri.parse("content://sms/" + (p.containsKey("box") ? p.get("box") : q.containsKey("box") ? q.get("box") : "inbox")),
                        "_id=?", new String[]{id});
                JSONObject o = new JSONObject();
                o.put("deleted", n);
                return new String[]{"200", o.toString()};
            }
            if (path.equals("/api/sms/send")) {
                String addr = p.get("address");
                String msg = p.get("body");
                SmsManager.getDefault().sendTextMessage(addr, null, msg, null, null);
                JSONObject o = new JSONObject();
                o.put("sent", true);
                return new String[]{"200", o.toString()};
            }
            if (path.equals("/api/ussd")) {
                String code = p.containsKey("code") ? p.get("code") : q.get("code");
                return new String[]{"200", sendUssd(code)};
            }
            if (path.equals("/api/sh")) {
                String cmd = p.containsKey("cmd") ? p.get("cmd") : q.get("cmd");
                String cwd = p.containsKey("cwd") ? p.get("cwd") : q.get("cwd");
                String to = p.containsKey("timeout") ? p.get("timeout") : q.get("timeout");
                return new String[]{"200", runShell(cmd, cwd, to)};
            }
            if (path.equals("/api/pty/info")) {
                JSONObject o = new JSONObject();
                boolean ok = Pty.available() && new File("/dev/ptmx").exists();
                o.put("available", ok);
                o.put("transport", "websocket");
                o.put("endpoint", "/api/pty");
                return new String[]{"200", o.toString()};
            }
            if (path.equals("/api/battery")) {
                return new String[]{"200", batteryJson()};
            }
            if (path.equals("/api/status")) {
                JSONObject o = new JSONObject();
                o.put("model", android.os.Build.MODEL);
                o.put("android", android.os.Build.VERSION.RELEASE);
                o.put("service", "mifi-helper");
                return new String[]{"200", o.toString()};
            }
            return new String[]{"404", jsonErr("not found")};
        } catch (Exception ex) {
            try {
                return new String[]{"500", jsonErr(ex.getMessage() == null ? ex.toString() : ex.getMessage())};
            } catch (Exception x) {
                return new String[]{"500", "{\"error\":\"unknown\"}"};
            }
        }
    }

    /* ------------------------------------------------------------------ *
     * Battery — real percentage (1% resolution), for the web status bar    *
     * ------------------------------------------------------------------ */

    /**
     * Android framework state, not the firmware's 5-bucket estimate that
     * AjaxSevlet exposes as bat_percent (see mifiservice-decompiled:
     * AjaxSevlet.java:197-208). ACTION_BATTERY_CHANGED is a sticky broadcast
     * and needs no permission, so registerReceiver(null, filter) hands back the
     * current state; the framework reads it from the qpnp charger driver.
     * Falls back to the kernel sysfs nodes if the broadcast is unavailable.
     */
    private String batteryJson() throws Exception {
        int level = -1, scale = 100, status = 0, plugged = 0, voltage = -1, temp = -1;
        String source = "android";
        String statusText;
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                status = b.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
                plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                voltage = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            }
        } catch (Exception e) {
        }
        statusText = batteryStatusText(status);
        if (level < 0 || scale <= 0) {
            // Framework had nothing: read the charger driver directly (world-readable).
            source = "sysfs";
            scale = 100;
            status = -1;
            level = readInt("/sys/class/power_supply/battery/capacity", -1);
            if (level < 0) level = readInt("/sys/class/power_supply/bms/capacity", -1);
            String st = readText("/sys/class/power_supply/battery/status");
            if (st != null && st.length() > 0) {
                statusText = st;
                String l = st.toLowerCase();
                if (l.indexOf("charg") >= 0) status = BatteryManager.BATTERY_STATUS_CHARGING;
                else if (l.indexOf("full") >= 0) status = BatteryManager.BATTERY_STATUS_FULL;
                else if (l.indexOf("discharg") >= 0) status = BatteryManager.BATTERY_STATUS_DISCHARGING;
            }
        }
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        JSONObject o = new JSONObject();
        o.put("percent", level < 0 ? -1 : Math.round(level * 100f / scale));
        o.put("level", level);
        o.put("scale", scale);
        o.put("charging", charging);
        o.put("status", statusText);
        o.put("plugged", batteryPlugText(plugged));
        o.put("voltage_mv", voltage);
        o.put("temperature_c", temp < 0 ? -1.0 : Math.round(temp / 10.0 * 10.0) / 10.0);
        o.put("source", source);
        return o.toString();
    }

    private static String batteryStatusText(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging";
            default: return "Unknown";
        }
    }

    private static String batteryPlugText(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "ac";
            case BatteryManager.BATTERY_PLUGGED_USB: return "usb";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "wireless";
            default: return "none";
        }
    }

    private static int readInt(String path, int def) {
        String s = readText(path);
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    /** First line of a (small) kernel file, or null when unreadable. */
    private static String readText(String path) {
        InputStream is = null;
        try {
            is = new java.io.FileInputStream(path);
            byte[] buf = new byte[64];
            int n = is.read(buf);
            return n <= 0 ? null : new String(buf, 0, n, "UTF-8").trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Exception e) {}
        }
    }

    private Uri smsUri(String box) {
        if (!"inbox".equals(box) && !"sent".equals(box) && !"draft".equals(box) && !"outbox".equals(box)) box = "inbox";
        return Uri.parse("content://sms/" + box);
    }

    private String smsList(String box, String limitStr) throws Exception {
        int limit = 100;
        try { limit = Integer.parseInt(limitStr); } catch (Exception e) {}
        if (limit > 500) limit = 500;
        Cursor c = getContentResolver().query(smsUri(box),
                new String[]{"_id", "address", "body", "date", "person", "read"}, null, null, "date DESC");
        JSONArray arr = new JSONArray();
        int n = 0;
        if (c != null) {
            while (c.moveToNext() && n < limit) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("address", c.getString(1) == null ? "" : c.getString(1));
                String b = c.getString(2);
                o.put("body", b == null ? "" : b);
                o.put("date", c.getLong(3));
                o.put("read", c.getInt(5));
                arr.put(o);
                n++;
            }
            c.close();
        }
        JSONObject r = new JSONObject();
        r.put("count", arr.length());
        r.put("messages", arr);
        return r.toString();
    }

    private String smsGet(String id, String box) throws Exception {
        Cursor c = getContentResolver().query(smsUri(box),
                new String[]{"_id", "address", "body", "date"}, "_id=?", new String[]{id}, null);
        JSONObject r = new JSONObject();
        if (c != null && c.moveToFirst()) {
            r.put("id", c.getLong(0));
            r.put("address", c.getString(1));
            r.put("body", c.getString(2));
            r.put("date", c.getLong(3));
            c.close();
        } else {
            r.put("error", "not found");
        }
        return r.toString();
    }

    private static final int SHELL_OUT_CAP = 256 * 1024;
    private static final String SHELL = "/system/bin/sh";

    /** App-private dir holding BusyBox applet symlinks; null until probed. */
    private volatile String bbDir;

    /** null = not probed yet. On this firmware `su` exists but SELinux denies apps. */
    private static Boolean suWorks = null;

    /**
     * Probe `su` once. Only returns true if it really yields uid 0, so a broken
     * su (e.g. "su: setuid failed: Operation not permitted") is detected instead
     * of silently producing a rootless, output-less shell.
     */
    private synchronized boolean suAvailable() {
        if (suWorks != null) return suWorks.booleanValue();
        boolean ok;
        try {
            String[] r = execScript(true, "id", "/", 6);
            ok = r[2].indexOf("uid=0") >= 0;
        } catch (Throwable t) {
            ok = false;
        }
        android.util.Log.i("MifiHelper", "su available=" + ok);
        suWorks = Boolean.valueOf(ok);
        return ok;
    }

    /**
     * Run cmd under `sh` (non-PTY), as the app user by default. If a working
     * `su` is present it is used to get root, otherwise commands run with the
     * helper's own uid. Script is piped over stdin to avoid quoting issues.
     */
    private String runShell(String cmd, String cwd, String timeoutStr) throws Exception {
        if (cmd == null || cmd.trim().length() == 0) {
            return jsonErr("empty cmd");
        }
        int timeout = 15;
        try { timeout = Integer.parseInt(timeoutStr); } catch (Exception e) {}
        if (timeout < 1) timeout = 1;
        if (timeout > 180) timeout = 180;
        if (cwd == null || cwd.trim().length() == 0) cwd = "/";

        String safeCwd = cwd.replace("'", "'\\''");
        StringBuilder script = new StringBuilder();
        script.append("cd '").append(safeCwd).append("' 2>/dev/null || cd /\n");
        // BusyBox `whoami` can't resolve Android uids ("unknown uid 10030"); derive
        // the app name from `id` instead. Function takes precedence over PATH.
        script.append("whoami() { __i=$(id); case \"$__i\" in *\\(*) __u=${__i#*\\(}; __u=${__u%%\\)*}; echo \"$__u\" ;; *) echo \"$__i\" ;; esac; }\n");
        script.append(cmd).append("\n");
        // No `printf` on this build; use the shell's echo + capture rc before it runs.
        script.append("__mifi_rc=$?\n");
        script.append("echo\n");
        script.append("echo \"__MIFI_RC__ $__mifi_rc $(pwd)\"\n");

        String[] r = execScript(suAvailable(), script.toString(), cwd, timeout);

        JSONObject o = new JSONObject();
        o.put("rc", r[0]);
        o.put("cwd", r[1]);
        o.put("out", r[2]);
        return o.toString();
    }

    /**
     * A non-interactive shell as the helper's own uid, with BusyBox applets on PATH.
     * The script is passed as an argv (`sh -c '<script>'`) and stdin is rewired to
     * /dev/null. Piping the script over stdin (the old approach) let any command
     * that reads stdin — cat, read, more, vi — swallow the rest of the script,
     * including the `__MIFI_RC__` marker, which corrupted rc/cwd and broke
     * interactive commands.
     */
    private Process newShell(String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(SHELL, "-c", "exec 0</dev/null\n" + script);
        pb.redirectErrorStream(true);
        pb.environment().put("PATH", shellPath());
        return pb.start();
    }

    private String shellPath() {
        String p = "/sbin:/system/sbin:/system/bin:/system/xbin";
        String bb = bbDir;
        if (bb != null && new java.io.File(bb).isDirectory()) p = p + ":" + bb;
        return p;
    }

    private static final String[] BUSYBOX_CANDIDATES = {
            "/system/bin/busybox", "/system/xbin/busybox", "/system/sbin/busybox", "/sbin/busybox"
    };

    /**
     * This firmware ships BusyBox but exposes no applet symlinks, and toolbox lacks
     * most text utilities (awk, sed, head, tail, find, free, ...). Install applet
     * symlinks into the app's private dir (no root needed) and put that dir on PATH
     * so the web terminal can run them. Native /system/bin tools keep priority.
     */
    private void installBusybox() {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "bin");
            bbDir = dir.getAbsolutePath();
            java.io.File bb = null;
            for (String c : BUSYBOX_CANDIDATES) {
                if (new java.io.File(c).exists()) { bb = new java.io.File(c); break; }
            }
            if (bb == null) {
                bbDir = null;
                android.util.Log.i("MifiHelper", "no busybox on device; terminal stays toolbox-only");
                return;
            }
            java.io.File marker = new java.io.File(dir, ".installed");
            if (!marker.exists()) {
                deleteRecursive(dir);
                dir.mkdirs();
                Process p = new ProcessBuilder(bb.getAbsolutePath(), "--install", "-s", dir.getAbsolutePath())
                        .redirectErrorStream(true).start();
                InputStream is = p.getInputStream();
                byte[] buf = new byte[1024];
                while (is.read(buf) > 0) { /* drain */ }
                p.waitFor();
                marker.createNewFile();
                android.util.Log.i("MifiHelper", "busybox applets installed at " + dir);
            }
            writeWhoamiShim(dir);
        } catch (Throwable t) {
            android.util.Log.w("MifiHelper", "busybox install failed", t);
        }
    }

    /**
     * BusyBox `whoami` cannot resolve Android app uids ("unknown uid 10030"), so
     * replace its symlink with a tiny script that derives the name from `id`.
     * Needed by both the one-shot shell and the interactive PTY shell (where the
     * shell-function shim from runShell does not apply).
     */
    private void writeWhoamiShim(java.io.File dir) {
        try {
            java.io.File f = new java.io.File(dir, "whoami");
            f.delete();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(("#!/system/bin/sh\n"
                    + "i=$(id 2>/dev/null)\n"
                    + "case \"$i\" in\n"
                    + "  *\\(*) u=${i#*\\(}; u=${u%%\\)*}; echo \"$u\" ;;\n"
                    + "  *) echo \"$i\" ;;\n"
                    + "esac\n").getBytes("UTF-8"));
            fos.close();
            f.setReadable(true, false);
            f.setExecutable(true, false);
        } catch (Throwable t) {
            android.util.Log.w("MifiHelper", "whoami shim failed", t);
        }
    }

    private static void deleteRecursive(java.io.File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) for (java.io.File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    /**
     * Run a script in a non-interactive shell. Returns {rc, cwd, output}.
     * root=true runs through `su -c sh` (script on stdin, kept for the rare case
     * a working root shell exists); root=false runs as the helper's uid with the
     * script as an argv and stdin on /dev/null.
     */
    private String[] execScript(boolean root, String script, String cwd, int timeout) throws Exception {
        Process proc;
        boolean feedStdin = false;
        if (root) {
            try {
                ProcessBuilder pb = new ProcessBuilder("su", "-c", "sh");
                pb.redirectErrorStream(true);
                pb.environment().put("PATH", shellPath());
                proc = pb.start();
                feedStdin = true;
            } catch (Exception e) {
                proc = newShell(script);
            }
        } else {
            proc = newShell(script);
        }
        final Process fp = proc;
        final StringBuilder out = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream is = fp.getInputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = is.read(b)) >= 0) {
                        synchronized (out) {
                            if (out.length() < SHELL_OUT_CAP) {
                                out.append(new String(b, 0, n, "UTF-8"));
                            }
                        }
                    }
                } catch (Exception e) {}
            }
        });
        reader.start();
        if (feedStdin) {
            try {
                OutputStream os = proc.getOutputStream();
                os.write(script.getBytes("UTF-8"));
                os.flush();
                os.close();
            } catch (Exception e) {}
        }

        long deadline = System.currentTimeMillis() + timeout * 1000L;
        boolean done = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                proc.exitValue();
                done = true;
                break;
            } catch (IllegalThreadStateException e) {
                Thread.sleep(100);
            }
        }
        String note = null;
        if (!done) {
            note = "timeout after " + timeout + "s";
            proc.destroy();
        }
        reader.join(1500);

        String outStr;
        synchronized (out) { outStr = out.toString(); }
        if (outStr.length() >= SHELL_OUT_CAP) {
            outStr = outStr.substring(0, SHELL_OUT_CAP) + "\n[output truncated at 256KB]";
        }
        String rc = "-1";
        String newCwd = (cwd == null || cwd.length() == 0) ? "/" : cwd;
        int idx = outStr.lastIndexOf("__MIFI_RC__");
        if (idx >= 0) {
            String tail = outStr.substring(idx + 11).trim();
            int sp = tail.indexOf(' ');
            if (sp > 0) {
                rc = tail.substring(0, sp);
                String pwd = tail.substring(sp + 1).trim();
                if (pwd.length() > 0) newCwd = pwd;
            }
            outStr = outStr.substring(0, idx);
        }
        outStr = outStr.replaceFirst("^[\r\n]+", "");
        if (note != null) outStr += "\n[" + note + "]";
        return new String[]{rc, newCwd, outStr};
    }

    private String sendUssd(String code) throws Exception {
        if (code == null || code.length() == 0) {
            JSONObject e = new JSONObject();
            e.put("error", "empty code");
            return e.toString();
        }
        lastUssd.set(null);
        Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(code)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
            String resp = lastUssd.get();
            if (resp != null) {
                JSONObject o = new JSONObject();
                o.put("code", code);
                o.put("response", resp);
                return o.toString();
            }
        }
        JSONObject o = new JSONObject();
        o.put("code", code);
        o.put("timeout", true);
        o.put("note", "sent, but no dialog captured within 15s");
        return o.toString();
    }
}
