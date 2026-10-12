package com.mifi.hotspot;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP API + halaman kontrol web. Tanpa dependency: ServerSocket + thread per koneksi.
 * Sama semangatnya dengan mifi-helper, tapi endpoint khusus hotspot.
 */
final class ApiServer {

    private final Context ctx;
    private final HotspotEngine engine;
    private volatile ServerSocket server;
    private volatile boolean running = false;
    private volatile int port = 0;
    private volatile String cachedPage;

    ApiServer(Context c) {
        this.ctx = c.getApplicationContext();
        this.engine = HotspotEngine.get(ctx);
    }

    boolean isRunning() {
        return running;
    }

    int port() {
        return port;
    }

    boolean start(int p) {
        if (running && p == port) return true;
        stop();
        try {
            port = p;
            server = new ServerSocket(p);
            running = true;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    acceptLoop();
                }
            }, "hotspot-api");
            t.setDaemon(true);
            t.start();
            engine.log("HTTP API siap di :" + p);
            return true;
        } catch (Throwable t) {
            engine.log("HTTP API gagal bind :" + p + " — " + t.getMessage());
            running = false;
            return false;
        }
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Throwable ignored) {
        }
        server = null;
    }

    // -- server ----------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            Socket socket = null;
            try {
                socket = server.accept();
                final Socket s = socket;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        handle(s);
                    }
                });
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                if (running) engine.log("accept: " + t.getMessage());
                break;
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));

            String requestLine = in.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String target = parts[1];

            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
            }

            int contentLength = 0;
            try {
                contentLength = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0");
            } catch (Throwable ignored) {
            }
            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(buf, read, contentLength - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read);
            }

            String path = target;
            Map<String, String> query = new LinkedHashMap<String, String>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String pair : target.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        query.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
                    }
                }
            }

            Resp resp;
            try {
                resp = route(method, path, headers, query, body);
            } catch (Throwable t) {
                resp = Resp.json(500, "{\"ok\":false,\"error\":\"" + esc(t.getClass().getSimpleName() + ": " + t.getMessage()) + "\"}");
            }
            write(s, resp);
        } catch (Throwable ignored) {
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private void write(Socket s, Resp r) throws Exception {
        byte[] payload = r.body.getBytes("UTF-8");
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(r.code).append(' ').append(reason(r.code)).append("\r\n")
                .append("Content-Type: ").append(r.type).append("\r\n")
                .append("Content-Length: ").append(payload.length).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Headers: X-Auth, Content-Type\r\n")
                .append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                .append("Connection: close\r\n\r\n");
        OutputStream out = s.getOutputStream();
        out.write(head.toString().getBytes("UTF-8"));
        out.write(payload);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    // -- routing ---------------------------------------------------------------------

    private Resp route(String method, String path, Map<String, String> h, Map<String, String> q, String body) {
        if ("OPTIONS".equals(method)) return Resp.json(204, "");

        if (path.equals("/") || path.equals("/index.html")) return Resp.html(page());
        if (path.equals("/api/health")) return Resp.json("{\"ok\":true,\"app\":\"hotspot\"}");
        if (path.equals("/api/status")) return Resp.json(statusJson());
        if (path.equals("/api/clients")) return Resp.json(clientsJson(engine.status()));

        if (!authed(h, q)) {
            return Resp.json(401, "{\"ok\":false,\"error\":\"PIN salah atau belum diisi (header X-Auth)\"}");
        }

        if (path.equals("/api/capabilities")) return Resp.json(capsJson());
        if (path.equals("/api/accessibility/open")) {
            engine.openAccessibilitySettings();
            return Resp.json("{\"ok\":true,\"hint\":\"centang WiFi Modem di daftar layanan aksesibilitas\"}");
        }
        if (path.equals("/api/ssid/read")) {
            String before = engine.status().ssidSystem;
            long beforeAt = Prefs.systemOnAt(ctx);
            if (!engine.readSystemSsid()) {
                return Resp.json(500, "{\"ok\":false,\"error\":\"layanan aksesibilitas belum aktif\"}");
            }
            String now = before;
            long until = System.currentTimeMillis() + 8000L;
            while (System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(400L);
                } catch (InterruptedException ignored) {
                }
                engine.invalidate();
                now = engine.status().ssidSystem;
                boolean gotSsid = !now.isEmpty() && !now.equals(before);
                boolean gotState = Prefs.systemOnAt(ctx) != beforeAt;
                if (gotSsid || gotState) break;
            }
            return Resp.json("{\"ok\":true,\"ssid\":\"" + esc(now) + "\",\"status\":" + statusJson() + "}");
        }
        if (path.equals("/api/log")) return Resp.json("{\"ok\":true,\"log\":\"" + esc(engine.logText()) + "\"}");

        if (path.equals("/api/hotspot/start")) {
            JSONObject o = bodyJson(body);
            String ssid = o.optString("ssid", Prefs.ssid(ctx));
            String pass = o.optString("pass", Prefs.pass(ctx));
            String band = o.optString("band", Prefs.band(ctx));
            if (o.has("ssid") || o.has("pass") || o.has("band")) {
                Prefs.save(ctx, ssid, pass, band, Prefs.pin(ctx), Prefs.port(ctx), Prefs.autostart(ctx));
            }
            boolean ok = engine.start(new Cfg(ssid, pass, band));
            return Resp.json(ok ? 200 : 500, "{\"ok\":" + ok + ",\"status\":" + statusJson() + "}");
        }

        if (path.equals("/api/hotspot/stop")) {
            boolean ok = engine.stop();
            return Resp.json(ok ? 200 : 500, "{\"ok\":" + ok + ",\"status\":" + statusJson() + "}");
        }

        if (path.equals("/api/settings")) {
            engine.openSettings();
            return Resp.json("{\"ok\":true}");
        }

        if (path.equals("/api/config")) {
            JSONObject o = bodyJson(body);
            String ssid = o.optString("ssid", Prefs.ssid(ctx));
            String pass = o.optString("pass", Prefs.pass(ctx));
            String band = o.optString("band", Prefs.band(ctx));
            String pin = o.optString("pin", Prefs.pin(ctx));
            int port = o.optInt("port", Prefs.port(ctx));
            boolean autostart = o.optBoolean("autostart", Prefs.autostart(ctx));

            if (pin.length() < 4) return Resp.json(400, "{\"ok\":false,\"error\":\"PIN minimal 4 karakter\"}");
            if (pass.length() > 0 && pass.length() < 8) return Resp.json(400, "{\"ok\":false,\"error\":\"password minimal 8 karakter\"}");

            engine.saveConfig(new Cfg(ssid, pass, band), pin, port, autostart);
            if (port != this.port) {
                start(port); // pindah port
            }
            return Resp.json("{\"ok\":true,\"status\":" + statusJson() + "}");
        }

        return Resp.json(404, "{\"ok\":false,\"error\":\"tidak ada: " + esc(path) + "\"}");
    }

    private boolean authed(Map<String, String> h, Map<String, String> q) {
        String pin = h.get("x-auth");
        if (pin == null) pin = q.get("pin");
        return pin != null && pin.equals(Prefs.pin(ctx));
    }

    // -- json ------------------------------------------------------------------------

    private String statusJson() {
        HotspotEngine.Status s = engine.status();
        Capabilities c = engine.caps();
        StringBuilder b = new StringBuilder(256);
        b.append('{')
                .append("\"ok\":true,")
                .append("\"enabled\":").append(s.enabled).append(',')
                .append("\"verified\":").append(s.verified).append(',')
                .append("\"ssid\":\"").append(esc(s.ssid)).append("\",")
                .append("\"ssidSystem\":\"").append(esc(s.ssidSystem)).append("\",")
                .append("\"ssidApp\":\"").append(esc(s.ssidApp)).append("\",")
                .append("\"backend\":\"").append(esc(s.backend)).append("\",")
                .append("\"error\":\"").append(esc(s.error)).append("\",")
                .append("\"checkedAt\":").append(s.checkedAt).append(',')
                .append("\"device\":\"").append(esc(c.device())).append("\",")
                .append("\"android\":\"").append(esc(c.release)).append("\",")
                .append("\"root\":").append(c.root).append(',')
                .append("\"accessibilityEnabled\":").append(accessibilityEnabledSafe()).append(',')
                .append("\"accessibilityConnected\":").append(engine.accessibilityConnected()).append(',')
                .append("\"port\":").append(port).append(',')
                .append("\"hotspotUrl\":\"").append(esc(hotspotUrl())).append("\",")
                .append("\"clients\":").append(clientsArray(s))
                .append('}');
        return b.toString();
    }

    private boolean accessibilityEnabledSafe() {
        try {
            return engine.accessibilityEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** URL untuk perangkat yang tersambung ke hotspot (diturunkan dari IP client). */
    private String hotspotUrl() {
        try {
            return TetherGuard.hotspotUrl(engine.status().clients, port);
        } catch (Throwable t) {
            return null;
        }
    }

    private String clientsJson(HotspotEngine.Status s) {
        return "{\"ok\":true,\"clients\":" + clientsArray(s) + "}";
    }

    private String clientsArray(HotspotEngine.Status s) {
        StringBuilder b = new StringBuilder();
        b.append('[');
        for (int i = 0; i < s.clients.size(); i++) {
            Client c = s.clients.get(i);
            if (i > 0) b.append(',');
            b.append("{\"ip\":\"").append(esc(c.ip))
                    .append("\",\"mac\":\"").append(esc(c.mac))
                    .append("\",\"name\":\"").append(esc(c.name))
                    .append("\",\"source\":\"").append(esc(c.source))
                    .append("\"}");
        }
        b.append(']');
        return b.toString();
    }

    private String capsJson() {
        Capabilities c = engine.caps();
        return "{"
                + "\"device\":\"" + esc(c.device()) + "\","
                + "\"android\":\"" + esc(c.release) + "\","
                + "\"sdk\":" + c.sdk + ","
                + "\"root\":" + c.root + ","
                + "\"tetherPermission\":" + c.tetherPermission + ","
                + "\"accessibilityEnabled\":" + engine.accessibilityEnabled() + ","
                + "\"accessibilityConnected\":" + engine.accessibilityConnected() + ","
                + "\"preferredBackend\":\"" + esc(c.preferredBackend()) + "\","
                + "\"hint\":\"" + esc(c.hint()) + "\","
                + "\"rootError\":\"" + esc(c.rootError) + "\""
                + "}";
    }

    private static JSONObject bodyJson(String body) {
        try {
            if (body != null && !body.trim().isEmpty()) return new JSONObject(body);
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }

    private String page() {
        if (cachedPage != null) return cachedPage;
        try {
            InputStream in = ctx.getResources().openRawResource(R.raw.control);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            cachedPage = new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            cachedPage = "<h1>WiFi Modem</h1><p>gagal memuat halaman: " + t.getMessage() + "</p>";
        }
        return cachedPage;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
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

    private static final class Resp {
        final int code;
        final String type;
        final String body;

        Resp(int code, String type, String body) {
            this.code = code;
            this.type = type;
            this.body = body == null ? "" : body;
        }

        static Resp json(String body) {
            return new Resp(200, "application/json; charset=utf-8", body);
        }

        static Resp json(int code, String body) {
            return new Resp(code, "application/json; charset=utf-8", body);
        }

        static Resp html(String body) {
            return new Resp(200, "text/html; charset=utf-8", body);
        }
    }
}
