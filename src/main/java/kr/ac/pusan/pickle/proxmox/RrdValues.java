package kr.ac.pusan.pickle.proxmox;

/** How RRD encodes the values both metric series read, in one place. */
public final class RrdValues {

    private RrdValues() {
    }

    /**
     * RRD carries byte counters as doubles (consolidated averages), so every
     * byte field of a metric point is rounded back to a whole count here. A
     * gap stays a gap: an absent counter maps to null and never to zero, which
     * a chart would draw as a measured floor.
     */
    public static Long bytes(Double value) {
        return value == null ? null : Math.round(value);
    }
}
