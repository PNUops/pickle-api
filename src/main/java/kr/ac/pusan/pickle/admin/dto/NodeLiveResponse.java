package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code NodeLive}: what the hypervisor says about one node
 * right now, beside the allocation ratios the database already knows. Every
 * measurement is nullable because the answer to "the host is not answering" is
 * this row with {@code reachable} false — not a missing row and not an error.
 *
 * <p>{@code reachable} false also covers a node the platform chose not to ask:
 * an OFFLINE node is skipped rather than waited on, and the answer is the same
 * one either way — no live measurement is known. Why it is unknown is on the
 * panel already, in the node's own status beside these numbers.
 *
 * <p>The measurements come from two independent hypervisor calls, so a
 * reachable node can still carry null storage: the storage half needs a right
 * the status half does not.
 */
public record NodeLiveResponse(
        long nodeId,
        String name,
        @Schema(description = "false = 이 노드의 Proxmox API가 응답하지 않음 — 나머지 필드는 null")
        boolean reachable,
        @Nullable Long memTotalBytes,
        @Nullable Long memUsedBytes,
        @Nullable Double cpu,
        @Nullable Long storageTotalBytes,
        @Nullable Long storageUsedBytes,
        @Nullable Instant checkedAt) {

    /** The unreachable answer: identity only, and the console renders the gap. */
    public static NodeLiveResponse unreachable(long nodeId, String name) {
        return new NodeLiveResponse(nodeId, name, false, null, null, null, null, null, null);
    }
}
