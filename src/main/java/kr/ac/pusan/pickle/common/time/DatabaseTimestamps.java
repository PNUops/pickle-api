package kr.ac.pusan.pickle.common.time;

import java.sql.Timestamp;
import java.time.Instant;

/** Preserve exact half-open comparisons on PostgreSQL's stored microsecond grid. */
public final class DatabaseTimestamps {
    private DatabaseTimestamps() { }
    public static Timestamp ceilingMicros(Instant value) {
        int remainder = value.getNano() % 1000;
        return Timestamp.from(remainder == 0 ? value : value.plusNanos(1000 - remainder));
    }
}
