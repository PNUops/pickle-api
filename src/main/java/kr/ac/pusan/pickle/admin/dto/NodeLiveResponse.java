package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code NodeLive}: what the hypervisor says about one node
 * right now, beside the allocation ratios the database already knows. Every
 * measurement is nullable because the answer to "the host is not answering" is
 * this row with {@code reachable} false — not a missing row and not an error.
 *
 * <p>Every node is asked, whatever status the operator gave it: an OFFLINE
 * node still runs the guests it had, so its live numbers are still part of the
 * platform's. {@code reachable} is therefore exactly "this node answered its
 * status probe just now".
 *
 * <p>The measurements come from two independent hypervisor calls, so a
 * reachable node can still carry null storage: the storage half needs a right
 * the status half does not. How many nodes are behind each platform sum is on
 * the summary itself, in {@code liveCoverage}.
 */
public record NodeLiveResponse(
        UUID nodeId,
        String name,
        @Schema(description = "true = 이 노드가 상태 조회에 응답함. false = 응답하지 않음이며 나머지 필드는 모두 null."
                + " true여도 스토리지 조회는 별도 권한이라 storage* 필드는 null일 수 있음")
        boolean reachable,
        @Nullable Long memTotalBytes,
        @Nullable Long memUsedBytes,
        @Nullable Double cpu,
        @Nullable Long storageTotalBytes,
        @Nullable Long storageUsedBytes,
        @Nullable Instant checkedAt) {

    /** The unreachable answer: identity only, and the console renders the gap. */
    public static NodeLiveResponse unreachable(UUID nodeId, String name) {
        return new NodeLiveResponse(nodeId, name, false, null, null, null, null, null, null);
    }
}
