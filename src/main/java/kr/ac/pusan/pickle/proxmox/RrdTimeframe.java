package kr.ac.pusan.pickle.proxmox;

import java.util.Locale;

/**
 * Timeframe of a PVE RRD query ({@code rrddata?timeframe=}). Mirrors the five
 * windows Proxmox keeps; resolution coarsens with the window (hour ≈ 1-minute
 * buckets, year ≈ daily). The consolidation function is fixed to
 * {@code AVERAGE} at the client layer — no caller charts peaks.
 */
public enum RrdTimeframe {
    HOUR, DAY, WEEK, MONTH, YEAR;

    String queryValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
