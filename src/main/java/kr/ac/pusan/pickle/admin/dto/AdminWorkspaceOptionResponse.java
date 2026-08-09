package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;

/**
 * Contract {@code AdminWorkspaceOption}: the announcement screen's workspace picker,
 * doubling as the admin workspace-list row since v0.19.0 ({@code kind} and
 * {@code createdAt} added — additive, picker consumers unaffected).
 */
public record AdminWorkspaceOptionResponse(long id, String name, long memberCount,
        WorkspaceKind kind, Instant createdAt) {
}
