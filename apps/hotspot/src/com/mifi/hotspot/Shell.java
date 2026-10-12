package com.mifi.hotspot;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Utilitas menjalankan proses. Dua jalur:
 *  - run(): shell biasa sebagai uid app (dipakai baca /proc, ip, dsb.)
 *  - su():   lewat `su -c` (root), dipakai untuk app_process & dumpsys.
 */
final class Shell {

    static final class Res {
        final int code;
        final String out;
        final String err;

        Res(int code, String out, String err) {
            this.code = code;
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
        }

        boolean ok() {
            return code == 0;
        }

        String best() {
            String e = err.trim();
            return e.isEmpty() ? out.trim() : e;
        }
    }

    private Shell() {}

    static Res run(String cmd, long timeoutMs) {
        return exec(new String[]{"sh", "-c", cmd}, timeoutMs);
    }

    static Res su(String cmd, long timeoutMs) {
        return exec(new String[]{"su", "-c", cmd}, timeoutMs);
    }

    private static Res exec(String[] argv, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(false).start();
            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();
            Thread to = pump(p.getInputStream(), out);
            Thread te = pump(p.getErrorStream(), err);

            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean exited = false;
            while (System.currentTimeMillis() < deadline) {
                try {
                    p.exitValue();
                    exited = true;
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    Thread.sleep(30);
                }
            }
            if (!exited) {
                try { p.destroy(); } catch (Throwable ignored) {}
                Thread.sleep(80);
            }
            to.join(400);
            te.join(400);

            int code = -1;
            try { code = p.exitValue(); } catch (Throwable ignored) {}
            if (!exited && code == -1) {
                return new Res(124, out.toString(), append(err.toString(), "timeout " + timeoutMs + "ms"));
            }
            return new Res(code, out.toString(), err.toString());
        } catch (Throwable t) {
            try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
            return new Res(-1, "", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Thread pump(final InputStream in, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (sb) { sb.append(line).append('\n'); }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String append(String a, String b) {
        return a.isEmpty() ? b : a + "\n" + b;
    }

    // -- deteksi root, di-cache supaya tidak spam prompt su tiap refresh --------------

    private static volatile long lastCheck = 0L;
    private static volatile boolean rootOk = false;
    private static volatile String rootError = "";

    static boolean hasRoot() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 30000L) return rootOk;
        Res r = su("id", 10000);
        rootOk = r.ok() && r.out.contains("uid=0");
        rootError = rootOk ? "" : r.best();
        lastCheck = now;
        return rootOk;
    }

    static String rootError() {
        hasRoot();
        return rootError;
    }

    static void invalidateRootCache() {
        lastCheck = 0L;
    }
}
