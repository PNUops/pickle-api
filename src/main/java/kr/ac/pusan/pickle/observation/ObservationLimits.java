package kr.ac.pusan.pickle.observation;

/** Technical bounds for one fixed-source sample and its bounded read projection. */
public final class ObservationLimits {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    public static final int MAX_BODY_BYTES = 512 * 1024;
    public static final int MAX_ENGINES = 32;
    public static final int MAX_SERIES = 2048;
    public static final int MAX_BUCKETS = 128;
    public static final int MAX_NAMES = 128;
    public static final int MAX_TEXT_BYTES = 256;
    public static final int SAMPLE_SECONDS = 60;
    public static final int RETENTION_DAYS = 90;
    public static final int FUTURE_SKEW_SECONDS = 300;
    public static final int MAX_GAP_SECONDS = 180;
    public static final int STALE_SECONDS = 180;
    public static final int RATE_PER_MINUTE = 120;
    public static final int MAX_READ_ROWS = 130_000;
    public static final int FETCH_SIZE = 128;
    public static final int MAX_OUTPUT_METRICS = 20_000;
    public static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    public static final int GC_BATCH = 1000;
    public static final int GC_BATCHES = 10;
    private ObservationLimits() { }
}
