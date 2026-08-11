package kr.ac.pusan.pickle.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessAudit;
import kr.ac.pusan.pickle.access.ResourceAccessMessages;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.resource.dto.ResourceSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;

/**
 * The merge itself, against fake adapters. No database: what is being pinned is
 * that a page assembled from several types is the same page a single ordered
 * list would have produced — and the ways that quietly stops being true are
 * arithmetic, not SQL.
 */
class ResourceIndexMergeTest {

    /** An adapter that serves a fixed, already-ordered list. */
    private static final class FakeAdapter implements ResourceTypeAdapter {
        private final ResourceType type;
        private final List<ResourceSummaryResponse> rows;
        private int lastLimit = -1;

        FakeAdapter(ResourceType type, List<ResourceSummaryResponse> rows) {
            this.type = type;
            this.rows = rows;
        }

        @Override
        public ResourceType type() {
            return type;
        }

        @Override
        public InventoryHead inventoryHead(AuthenticatedUser actor, UUID workspaceId, int limit) {
            lastLimit = limit;
            return new InventoryHead(rows.subList(0, Math.min(limit, rows.size())), rows.size());
        }

        @Override
        public Optional<ResourceIdentity> identify(long resourceId) {
            return Optional.empty();
        }

        @Override
        public Optional<ResourceIdentity> identifyByPublicId(UUID publicId) {
            return Optional.empty();
        }

        @Override
        public ResourceAccessMessages accessMessages() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResourceAccessAudit accessAudit() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Long> idsOwnedByWorkspace(long workspaceId) {
            return List.of();
        }

        @Override
        public long countLiveInWorkspace(long workspaceId) {
            return 0;
        }
    }

    private static ResourceSummaryResponse row(ResourceType type, String name, Instant createdAt) {
        return new ResourceSummaryResponse(UUID.randomUUID(), type, name, null, "ACTIVE",
                UUID.randomUUID(), "ws", false, List.of(), false, createdAt);
    }

    private static Instant at(int minute) {
        return Instant.parse("2026-08-11T00:00:00Z").plusSeconds(minute * 60L);
    }

    private static List<String> namesOf(List<ResourceSummaryResponse> rows) {
        return rows.stream().map(ResourceSummaryResponse::name).toList();
    }

    @Test
    void mergedPageInterleavesTypesNewestFirst() {
        // Two types whose rows interleave in time. Asking each for "page 0" and
        // concatenating would put all of one type first; the answer has to be
        // the order a single list would have had.
        var vms = new FakeAdapter(ResourceType.VM,
                List.of(row(ResourceType.VM, "vm-5", at(5)), row(ResourceType.VM, "vm-1", at(1))));
        var keys = new FakeAdapter(ResourceType.LLM_API_KEY,
                List.of(row(ResourceType.LLM_API_KEY, "key-4", at(4)),
                        row(ResourceType.LLM_API_KEY, "key-2", at(2))));
        var service = new ResourceIndexService(List.of(vms, keys));

        var page = service.list(null, null, null, 0, 10);
        assertThat(namesOf(page.content()))
                .containsExactly("vm-5", "key-4", "key-2", "vm-1");
        assertThat(page.totalElements()).isEqualTo(4);
    }

    @Test
    void noRowRepeatsOrSkipsAcrossConsecutivePages() {
        // The failure this whole design exists for: a per-type page N walk
        // produces a first page that looks right and later pages that drop rows.
        List<ResourceSummaryResponse> a = new ArrayList<>();
        List<ResourceSummaryResponse> b = new ArrayList<>();
        for (int i = 24; i >= 0; i--) {
            boolean vm = i % 3 == 0;
            (vm ? a : b).add(row(vm ? ResourceType.VM : ResourceType.LLM_API_KEY, "r-" + i, at(i)));
        }
        var service = new ResourceIndexService(List.of(new FakeAdapter(ResourceType.VM, a),
                new FakeAdapter(ResourceType.LLM_API_KEY, b)));

        List<String> walked = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            walked.addAll(namesOf(service.list(null, null, null, p, 10).content()));
        }
        List<String> expected = new ArrayList<>();
        for (int i = 24; i >= 0; i--) {
            expected.add("r-" + i);
        }
        assertThat(walked).containsExactlyElementsOf(expected);
        assertThat(walked).doesNotHaveDuplicates();
    }

    @Test
    void equalTimestampsPageDeterministically() {
        // Rows created in the same instant are what batch creation produces, and
        // they are exactly where an unstable or id-based tiebreak starts
        // disagreeing with the database.
        Instant same = at(7);
        var a = new FakeAdapter(ResourceType.VM,
                List.of(row(ResourceType.VM, "a-2", same), row(ResourceType.VM, "a-1", same)));
        var b = new FakeAdapter(ResourceType.LLM_API_KEY,
                List.of(row(ResourceType.LLM_API_KEY, "b-2", same),
                        row(ResourceType.LLM_API_KEY, "b-1", same)));
        var service = new ResourceIndexService(List.of(a, b));

        var first = namesOf(service.list(null, null, null, 0, 2).content());
        var firstAgain = namesOf(service.list(null, null, null, 0, 2).content());
        var second = namesOf(service.list(null, null, null, 1, 2).content());

        assertThat(first).isEqualTo(firstAgain);
        assertThat(first).doesNotContainAnyElementsOf(second);
        var walked = new ArrayList<>(first);
        walked.addAll(second);
        assertThat(walked).containsExactlyInAnyOrder("a-1", "a-2", "b-1", "b-2");
        // Within one type the adapter's own order survives the merge.
        assertThat(walked.indexOf("a-2")).isLessThan(walked.indexOf("a-1"));
    }

    @Test
    void totalsAreTheSumOfTypeTotals() {
        var a = new FakeAdapter(ResourceType.VM, List.of(row(ResourceType.VM, "a", at(2))));
        var b = new FakeAdapter(ResourceType.LLM_API_KEY,
                List.of(row(ResourceType.LLM_API_KEY, "b", at(3)),
                        row(ResourceType.LLM_API_KEY, "c", at(1))));
        var service = new ResourceIndexService(List.of(a, b));

        var page = service.list(null, null, null, 0, 2);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).hasSize(2);
    }

    @Test
    void aTypeWithNoRowsDoesNotBreakThePage() {
        var empty = new FakeAdapter(ResourceType.LLM_API_KEY, List.of());
        var full = new FakeAdapter(ResourceType.VM, List.of(row(ResourceType.VM, "only", at(1))));
        var service = new ResourceIndexService(List.of(empty, full));

        var page = service.list(null, null, null, 0, 10);
        assertThat(namesOf(page.content())).containsExactly("only");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void aDeepPageIsEmptyButKeepsItsTotals() {
        // Guards the head-size arithmetic: a far page must answer an empty
        // content with honest totals, never a short page that reads as the end.
        var a = new FakeAdapter(ResourceType.VM, List.of(row(ResourceType.VM, "a", at(1))));
        var service = new ResourceIndexService(List.of(a));

        var page = service.list(null, null, null, 40, 10);
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    void eachTypeIsAskedForEnoughRowsToCoverThePage() {
        // The head has to cover the whole offset, or a later page silently
        // loses the rows that would have sorted into it.
        var a = new FakeAdapter(ResourceType.VM, List.of(row(ResourceType.VM, "a", at(1))));
        var service = new ResourceIndexService(List.of(a));

        service.list(null, null, null, 3, 10);
        assertThat(a.lastLimit).isEqualTo(40);
    }
}
