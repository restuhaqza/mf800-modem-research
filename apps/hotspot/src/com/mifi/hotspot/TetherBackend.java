package com.mifi.hotspot;

/** Konfigurasi hotspot yang diminta pengguna. */
final class Cfg {

    final String ssid;
    final String pass;
    final String band; // "auto" | "2.4" | "5"

    Cfg(String ssid, String pass, String band) {
        this.ssid = ssid == null ? "" : ssid;
        this.pass = pass == null ? "" : pass;
        this.band = band == null ? "auto" : band;
    }

    int bandCode() {
        if ("2.4".equals(band)) return 1;
        if ("5".equals(band)) return 2;
        return 0;
    }
}

/** Satu strategi menyalakan/mematikan hotspot. */
interface TetherBackend {

    String id();

    String label();

    /** Apakah strategi ini masuk akal dicoba di device ini. */
    boolean available();

    boolean start(Cfg cfg);

    boolean stop();

    String lastError();
}
