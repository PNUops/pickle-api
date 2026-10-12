package kr.ac.pusan.pickle.inventory;

import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validated capacity reserved by an external node registration tool. This pure
 * parser does not enable placement or change the legacy monitoring denominators.
 */
public record PlacementCapacity(CapacityAmounts physical, CapacityAmounts reserved,
        CapacityAmounts allocatable, Instant measuredAt, CpuPolicy cpuPolicy) {

    private static final Set<String> DOCUMENT_FIELDS = Set.of(
            "schema_version", "physical", "reserved", "allocatable", "measured_at");
    private static final Set<String> SHARED_CPU_DOCUMENT_FIELDS = Set.of(
            "schema_version", "physical", "reserved", "allocatable", "measured_at", "cpu_policy");
    private static final Set<String> RESOURCE_FIELDS = Set.of("cpu_threads", "memory_mb", "disk_gb");
    private static final Set<String> CPU_POLICY_FIELDS = Set.of("allocation_ratio", "committed_vcpu");

    public PlacementCapacity(CapacityAmounts physical, CapacityAmounts reserved,
            CapacityAmounts allocatable, Instant measuredAt) {
        this(physical, reserved, allocatable, measuredAt, new CpuPolicy(1, 0));
    }

    public PlacementCapacity {
        if (physical == null || reserved == null || allocatable == null || measuredAt == null || cpuPolicy == null
                || physical.cpuThreads() > Integer.MAX_VALUE || physical.memoryMb() > Integer.MAX_VALUE
                || !consistentCpu(physical.cpuThreads(), reserved.cpuThreads(), allocatable.cpuThreads(), cpuPolicy)
                || !consistent(physical.memoryMb(), reserved.memoryMb(), allocatable.memoryMb())
                || !consistent(physical.diskGb(), reserved.diskGb(), allocatable.diskGb())) {
            throw invalid();
        }
    }

    /** Host CPU reserve is physical; external commitments are already allocated vCPUs. */
    public record CpuPolicy(long allocationRatio, long committedVcpu) {
        public CpuPolicy {
            if ((allocationRatio != 1 && allocationRatio != 2) || committedVcpu < 0) {
                throw invalid();
            }
        }
    }

    public record CapacityAmounts(long cpuThreads, long memoryMb, long diskGb) {
        public CapacityAmounts {
            if (cpuThreads < 0 || memoryMb < 0 || diskGb < 0) {
                throw invalid();
            }
        }
    }

    /**
     * Absence is distinct from malformed data: a feature-disabled legacy caller
     * may retain its existing behavior, but must never treat invalid labels as absent.
     * The memory column already excludes the reserve; use allocatable directly.
     */
    public static Optional<PlacementCapacity> read(Map<String, ?> labels,
            int nodeCpuThreads, int nodeMemoryMb, Long nodeDiskCapacityGb, Instant observedAt) {
        if (labels == null || !labels.containsKey("placement_capacity")) {
            return Optional.empty();
        }
        if (!(labels.get("placement_capacity") instanceof Map<?, ?> candidate) || observedAt == null) {
            throw invalid();
        }
        long version = integer(candidate.get("schema_version"));
        Map<?, ?> document;
        CpuPolicy cpuPolicy;
        if (version == 1) {
            document = exactMap(candidate, DOCUMENT_FIELDS);
            cpuPolicy = new CpuPolicy(1, 0);
        } else if (version == 2) {
            document = exactMap(candidate, SHARED_CPU_DOCUMENT_FIELDS);
            Map<?, ?> policy = exactMap(document.get("cpu_policy"), CPU_POLICY_FIELDS);
            cpuPolicy = new CpuPolicy(integer(policy.get("allocation_ratio")), integer(policy.get("committed_vcpu")));
        } else {
            throw invalid();
        }
        Instant measuredAt;
        try {
            if (!(document.get("measured_at") instanceof String value)) {
                throw invalid();
            }
            measuredAt = Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid();
        }
        PlacementCapacity result = new PlacementCapacity(resources(document.get("physical")),
                resources(document.get("reserved")), resources(document.get("allocatable")), measuredAt, cpuPolicy);
        if (measuredAt.isAfter(observedAt)
                || result.physical().cpuThreads() != nodeCpuThreads
                || result.allocatable().memoryMb() != nodeMemoryMb
                || nodeDiskCapacityGb == null || result.physical().diskGb() != nodeDiskCapacityGb) {
            throw invalid();
        }
        return Optional.of(result);
    }

    private static CapacityAmounts resources(Object value) {
        Map<?, ?> values = exactMap(value, RESOURCE_FIELDS);
        return new CapacityAmounts(integer(values.get("cpu_threads")), integer(values.get("memory_mb")),
                integer(values.get("disk_gb")));
    }

    private static Map<?, ?> exactMap(Object value, Set<String> fields) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(fields)) {
            throw invalid();
        }
        return map;
    }

    private static long integer(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger number) {
            try {
                return number.longValueExact();
            } catch (ArithmeticException e) {
                throw invalid();
            }
        }
        throw invalid();
    }

    private static boolean consistent(long physical, long reserved, long allocatable) {
        return physical > 0 && reserved < physical && allocatable == physical - reserved;
    }

    private static boolean consistentCpu(long physical, long reserved, long allocatable, CpuPolicy policy) {
        if (physical <= 0 || reserved >= physical) {
            return false;
        }
        try {
            long available = Math.subtractExact(Math.multiplyExact(physical - reserved, policy.allocationRatio()),
                    policy.committedVcpu());
            return available > 0 && allocatable == available;
        } catch (ArithmeticException e) {
            return false;
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("노드의 예약 용량 정보를 확인할 수 없습니다");
    }
}
