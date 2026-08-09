package kr.ac.pusan.pickle.resource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.resource.dto.ResourceSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The type-agnostic inventory behind {@code GET /resources}: what the console
 * shows on the dashboard and in a workspace's resource list.
 *
 * <p>With one resource type this delegates; with several it will have to merge
 * pages that each know their own ordering, which is a real decision (a merged
 * page cannot be produced by asking each type for page N). It is deliberately
 * not solved before there is a second type to solve it against — see
 * `convention/add-resource-type.md`.
 */
@Service
public class ResourceIndexService {

    private final Map<ResourceType, ResourceTypeAdapter> adapters;

    public ResourceIndexService(List<ResourceTypeAdapter> adapters) {
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(ResourceTypeAdapter::type, Function.identity()));
    }

    @Transactional(readOnly = true)
    public PageResponse<ResourceSummaryResponse> list(AuthenticatedUser actor, ResourceType type,
            Long workspaceId, int page, int size) {
        // Newest first by creation time rather than by id: an id is an opaque
        // handle, and ordering by it would break the day one stops being a
        // number.
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        ResourceTypeAdapter adapter = type != null ? adapters.get(type) : soleAdapter();
        if (adapter == null) {
            return PageResponse.of(List.of(), Page.empty(pageable));
        }
        Page<ResourceSummaryResponse> result = adapter.page(actor, workspaceId, pageable);
        return PageResponse.of(result.getContent(), result);
    }

    /**
     * Untyped listing while exactly one type exists. A second type turns this
     * into the merge described above, which is why it fails loudly rather than
     * quietly returning one type's rows as if they were everything.
     */
    private ResourceTypeAdapter soleAdapter() {
        if (adapters.size() != 1) {
            throw new IllegalStateException(
                    "Untyped resource listing needs a merge strategy once more than one type exists");
        }
        return adapters.values().iterator().next();
    }
}
