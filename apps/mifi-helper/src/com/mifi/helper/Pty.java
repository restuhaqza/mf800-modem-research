package com.mifi.helper;

/**
 * Thin wrapper over libmifipty.so: allocates a real PTY (bionic has no
 * openpty/forkpty, so the native code drives /dev/ptmx itself) and runs an
 * interactive shell on it. Used by the WebSocket terminal endpoint.
 */
public final class Pty {

    private static Boolean loaded;

    private Pty() {}

    /** True if the native library is present and loadable on this device. */
    public static synchronized boolean available() {
        if (loaded == null) {
            try {
                System.loadLibrary("mifipty");
                loaded = Boolean.TRUE;
            } catch (Throwable t) {
                android.util.Log.w("MifiHelper", "libmifipty unavailable", t);
                loaded = Boolean.FALSE;
            }
        }
        return loaded.booleanValue();
    }

    /** @return master fd (>= 0) or negative error code. */
    public static native int nativeSpawn(int cols, int rows, String shell, String path, String home);

    /** Read pty output into buf; returns bytes read, 0/negative on EOF/error. */
    public static native int nativeRead(int fd, byte[] buf);

    /** Write some bytes to the pty; returns bytes written. */
    public static native int nativeWrite(int fd, byte[] buf, int off, int len);

    /** Update the pty window size (the kernel raises SIGWINCH). */
    public static native void nativeResize(int fd, int cols, int rows);

    /** Hang up the child process group and close the master fd. */
    public static native void nativeClose(int fd);

    public static void writeAll(int fd, byte[] data) {
        int off = 0;
        while (off < data.length) {
            int n = nativeWrite(fd, data, off, data.length - off);
            if (n <= 0) break;
            off += n;
        }
    }
}
