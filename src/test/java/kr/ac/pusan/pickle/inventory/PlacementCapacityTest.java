package kr.ac.pusan.pickle.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PlacementCapacityTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void preservesLegacyColumnMeaningsWithoutSubtractingMemoryReserveTwice() {
        PlacementCapacity capacity = read(document());
        assertThat(capacity.physical()).isEqualTo(new PlacementCapacity.CapacityAmounts(32, 65536, 1000));
        assertThat(capacity.reserved()).isEqualTo(new PlacementCapacity.CapacityAmounts(4, 8192, 200));
        assertThat(capacity.allocatable()).isEqualTo(new PlacementCapacity.CapacityAmounts(28, 57344, 800));
        assertThat(capacity.measuredAt()).isEqualTo(NOW);
        assertThat(capacity.cpuPolicy()).isEqualTo(new PlacementCapacity.CpuPolicy(1, 0));
    }

    @Test
    void distinguishesAbsentLegacyMetadataFromPresentMalformedMetadata() {
        assertThat(PlacementCapacity.read(null, 32, 57344, 1000L, NOW)).isEmpty();
        assertThat(PlacementCapacity.read(Map.of("gpu", true), 32, 57344, 1000L, NOW)).isEmpty();
        Map<String, Object> labels = new HashMap<>();
        labels.put("placement_capacity", null);
        assertThatThrownBy(() -> PlacementCapacity.read(labels, 32, 57344, 1000L, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDocuments")
    void rejectsMalformedOrAmbiguousMetadata(String name, Consumer<Map<String, Object>> change) {
        Map<String, Object> document = document();
        change.accept(document);
        assertThatThrownBy(() -> read(document)).isInstanceOf(IllegalStateException.class);
    }

    static Stream<Arguments> invalidDocuments() {
        return Stream.of(
                scenario("unknown schema", d -> d.put("schema_version", 3)),
                scenario("text schema", d -> d.put("schema_version", "1")),
                scenario("decimal schema", d -> d.put("schema_version", 1.0)),
                scenario("unknown document field", d -> d.put("effective", 1)),
                scenario("missing resource group", d -> d.remove("reserved")),
                scenario("unknown resource dimension", d -> group(d, "physical").put("swap", 0)),
                scenario("missing resource dimension", d -> group(d, "physical").remove("disk_gb")),
                scenario("floating capacity", d -> group(d, "physical").put("disk_gb", 1000.0)),
                scenario("decimal capacity", d -> group(d, "physical").put("disk_gb", new BigDecimal("1000"))),
                scenario("boolean capacity", d -> group(d, "physical").put("disk_gb", true)),
                scenario("text capacity", d -> group(d, "physical").put("disk_gb", "1000")),
                scenario("overflow capacity", d -> group(d, "physical").put("disk_gb", BigInteger.ONE.shiftLeft(64))),
                scenario("negative reserve", d -> group(d, "reserved").put("memory_mb", -1)),
                scenario("reserve consumes all capacity", d -> group(d, "reserved").put("disk_gb", 1000)),
                scenario("reserve exceeds capacity", d -> group(d, "reserved").put("cpu_threads", 33)),
                scenario("inconsistent allocation", d -> group(d, "allocatable").put("memory_mb", 65536)),
                scenario("future measurement", d -> d.put("measured_at", NOW.plusSeconds(1).toString())),
                scenario("timestamp without timezone", d -> d.put("measured_at", "2026-09-16T00:00:00")),
                scenario("invalid timestamp", d -> d.put("measured_at", "yesterday")));
    }

    @Test
    void rejectsLegacyColumnsThatDoNotMatchTheirEstablishedMeanings() {
        Map<String, Object> labels = Map.of("placement_capacity", document());
        assertThatThrownBy(() -> PlacementCapacity.read(labels, 28, 57344, 1000L, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PlacementCapacity.read(labels, 32, 65536, 1000L, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PlacementCapacity.read(labels, 32, 49152, 1000L, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PlacementCapacity.read(labels, 32, 57344, 800L, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PlacementCapacity.read(labels, 32, 57344, null, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsExactIntegralJsonRepresentationsAndUnrelatedNodeLabels() {
        Map<String, Object> document = document();
        group(document, "physical").put("disk_gb", BigInteger.valueOf(1000));
        document.put("schema_version", 1L);
        assertThat(PlacementCapacity.read(Map.of("placement_capacity", document, "gpu", true),
                32, 57344, 1000L, NOW)).isPresent();
    }

    @Test
    void sharesOnlyUnreservedCpuAndSubtractsExternalCommitmentInVcpuUnits() {
        Map<String, Object> document = sharedCpuDocument();
        PlacementCapacity capacity = read(document);
        assertThat(capacity.cpuPolicy()).isEqualTo(new PlacementCapacity.CpuPolicy(2, 15));
        assertThat(capacity.physical().cpuThreads()).isEqualTo(32);
        assertThat(capacity.reserved().cpuThreads()).isEqualTo(4);
        assertThat(capacity.allocatable()).isEqualTo(new PlacementCapacity.CapacityAmounts(41, 57344, 800));
    }

    @Test
    void explicitRatioOneKeepsCommitmentSeparateFromPhysicalReserve() {
        Map<String, Object> document = sharedCpuDocument();
        group(document, "cpu_policy").put("allocation_ratio", 1);
        group(document, "allocatable").put("cpu_threads", 13);
        assertThat(read(document).allocatable().cpuThreads()).isEqualTo(13);
        group(document, "cpu_policy").put("committed_vcpu", 0);
        group(document, "allocatable").put("cpu_threads", 28);
        assertThat(read(document).allocatable()).isEqualTo(read(document()).allocatable());
    }

    @Test
    void sharedCpuBudgetSupportsLongAmountsWithoutChangingPhysicalIntegerColumns() {
        Map<String, Object> document = sharedCpuDocument();
        group(document, "physical").put("cpu_threads", Integer.MAX_VALUE);
        group(document, "reserved").put("cpu_threads", 0);
        group(document, "cpu_policy").put("allocation_ratio", BigInteger.valueOf(2));
        group(document, "cpu_policy").put("committed_vcpu", BigInteger.ZERO);
        group(document, "allocatable").put("cpu_threads", 2L * Integer.MAX_VALUE);
        PlacementCapacity capacity = PlacementCapacity.read(Map.of("placement_capacity", document),
                Integer.MAX_VALUE, 57344, 1000L, NOW).orElseThrow();
        assertThat(capacity.allocatable().cpuThreads()).isEqualTo(4294967294L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSharedCpuDocuments")
    void rejectsInvalidSharedPolicyAndMixedVersionMetadata(String name, Consumer<Map<String, Object>> change) {
        Map<String, Object> document = sharedCpuDocument();
        change.accept(document);
        assertThatThrownBy(() -> read(document)).isInstanceOf(IllegalStateException.class);
    }

    static Stream<Arguments> invalidSharedCpuDocuments() {
        return Stream.of(
                scenario("missing policy", d -> d.remove("cpu_policy")),
                scenario("legacy version with shared policy", d -> d.put("schema_version", 1)),
                scenario("unknown top-level field", d -> d.put("available_cpu", 41)),
                scenario("missing committed amount", d -> group(d, "cpu_policy").remove("committed_vcpu")),
                scenario("unknown policy field", d -> group(d, "cpu_policy").put("weight", 1)),
                scenario("boolean ratio", d -> group(d, "cpu_policy").put("allocation_ratio", true)),
                scenario("floating ratio", d -> group(d, "cpu_policy").put("allocation_ratio", 2.0)),
                scenario("text ratio", d -> group(d, "cpu_policy").put("allocation_ratio", "2")),
                scenario("unsupported ratio", d -> group(d, "cpu_policy").put("allocation_ratio", 3)),
                scenario("zero ratio", d -> group(d, "cpu_policy").put("allocation_ratio", 0)),
                scenario("negative ratio", d -> group(d, "cpu_policy").put("allocation_ratio", -1)),
                scenario("overflow ratio", d -> group(d, "cpu_policy").put("allocation_ratio", BigInteger.ONE.shiftLeft(64))),
                scenario("negative committed amount", d -> group(d, "cpu_policy").put("committed_vcpu", -1)),
                scenario("boolean committed amount", d -> group(d, "cpu_policy").put("committed_vcpu", false)),
                scenario("decimal committed amount", d -> group(d, "cpu_policy").put("committed_vcpu", new BigDecimal("15"))),
                scenario("text committed amount", d -> group(d, "cpu_policy").put("committed_vcpu", "15")),
                scenario("overflow committed amount", d -> group(d, "cpu_policy").put("committed_vcpu", BigInteger.ONE.shiftLeft(64))),
                scenario("commitment consumes shared budget", d -> {
                    group(d, "cpu_policy").put("committed_vcpu", 56);
                    group(d, "allocatable").put("cpu_threads", 0);
                }),
                scenario("commitment exceeds shared budget", d -> group(d, "cpu_policy").put("committed_vcpu", Long.MAX_VALUE)),
                scenario("host reserve consumes physical cpu", d -> group(d, "reserved").put("cpu_threads", 32)),
                scenario("wrong external commitment accounting", d -> group(d, "allocatable").put("cpu_threads", 26)),
                scenario("memory cannot be overcommitted", d -> group(d, "allocatable").put("memory_mb", 114688)),
                scenario("disk cannot be overcommitted", d -> group(d, "allocatable").put("disk_gb", 1600)),
                scenario("overflow allocation", d -> group(d, "allocatable").put("cpu_threads", BigInteger.ONE.shiftLeft(64))),
                scenario("unknown capacity dimension", d -> group(d, "reserved").put("external_cpu", 15)));
    }

    private static Arguments scenario(String name, Consumer<Map<String, Object>> change) {
        return Arguments.of(name, change);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> group(Map<String, Object> document, String name) {
        return (Map<String, Object>) document.get(name);
    }

    private static Map<String, Object> document() {
        return new HashMap<>(Map.of("schema_version", 1, "measured_at", NOW.toString(),
                "physical", new HashMap<>(Map.of("cpu_threads", 32, "memory_mb", 65536, "disk_gb", 1000L)),
                "reserved", new HashMap<>(Map.of("cpu_threads", 4, "memory_mb", 8192, "disk_gb", 200L)),
                "allocatable", new HashMap<>(Map.of("cpu_threads", 28, "memory_mb", 57344, "disk_gb", 800L))));
    }

    private static Map<String, Object> sharedCpuDocument() {
        Map<String, Object> value = document();
        value.put("schema_version", 2);
        value.put("cpu_policy", new HashMap<>(Map.of("allocation_ratio", 2, "committed_vcpu", 15)));
        group(value, "allocatable").put("cpu_threads", 41);
        return value;
    }

    private static PlacementCapacity read(Map<String, Object> document) {
        return PlacementCapacity.read(Map.of("placement_capacity", document), 32, 57344, 1000L, NOW)
                .orElseThrow();
    }
}
