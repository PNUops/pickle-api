package kr.ac.pusan.pickle.resource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.resource.dto.ResourceSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The type-agnostic inventory behind {@code GET /resources}: what the console
 * shows on the dashboard and in a workspace's resource list.
 *
 * <p>A merged page cannot be produced by asking each type for page N — a type
 * that runs out shifts every later offset, and the result looks right on the
 * first page. So each type is asked for the first {@code (page + 1) * size}
 * rows of its own ordering and the merge slices the page out of the combined
 * head. That head is always large enough: the global order restricted to one
 * type is that type's own order, so no row outside a type's first
 * {@code (page + 1) * size} can appear that early globally.
 *
 * <p><b>The merge never compares ids.</b> Rows are ordered by creation time,
 * ties broken by type name, and equal keys keep the order their adapter
 * delivered them in — {@link List#sort} is stable, and that is what carries
 * each type's own tiebreak through without the caller having to know it. The
 * tempting alternative, breaking ties on the public UUID, would be wrong in a
 * way that hides: Java compares a UUID as two signed longs and PostgreSQL
 * orders it as unsigned bytes, so the database's idea of "the next row" and
 * this code's would diverge on exactly the same-instant rows that batch
 * creation produces, dropping some from every page and repeating others.
 */
@Service
public class ResourceIndexService {

    private final Map<ResourceType, ResourceTypeAdapter> adapters;
    /** Fixed order, so a page requested twice is assembled identically. */
    private final List<ResourceTypeAdapter> ordered;

    public ResourceIndexService(List<ResourceTypeAdapter> adapters) {
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(ResourceTypeAdapter::type, Function.identity()));
        this.ordered = adapters.stream()
                .sorted(Comparator.comparing(a -> a.type().name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<ResourceSummaryResponse> list(AuthenticatedUser actor, ResourceType type,
            UUID workspaceId, int page, int size) {
        List<ResourceTypeAdapter> targets;
        if (type == null) {
            targets = ordered;
        } else {
            ResourceTypeAdapter adapter = adapters.get(type);
            if (adapter == null) {
                return new PageResponse<>(List.of(), page, size, 0, 0);
            }
            targets = List.of(adapter);
        }
        // Long arithmetic first: a caller asking for a far page must not wrap
        // the head size into something small and get a plausible short page.
        long head = Math.min((long) (page + 1) * size, Integer.MAX_VALUE);

        List<ResourceSummaryResponse> merged = new ArrayList<>();
        long total = 0;
        for (ResourceTypeAdapter adapter : targets) {
            ResourceTypeAdapter.InventoryHead contribution =
                    adapter.inventoryHead(actor, workspaceId, (int) head);
            merged.addAll(contribution.rows());
            total += contribution.totalElements();
        }
        // Newest first. The type name only settles ties across types; within a
        // type the sort's stability preserves what the adapter returned.
        merged.sort(Comparator.comparing(ResourceSummaryResponse::createdAt).reversed()
                .thenComparing(row -> row.type().name()));

        long from = Math.min((long) page * size, merged.size());
        long to = Math.min(from + size, merged.size());
        List<ResourceSummaryResponse> content = merged.subList((int) from, (int) to);
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(List.copyOf(content), page, size, total, totalPages);
    }
}
