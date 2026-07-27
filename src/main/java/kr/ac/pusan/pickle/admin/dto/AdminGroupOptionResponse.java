package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.group.GroupKind;

/**
 * Contract {@code AdminGroupOption}: the announcement screen's group picker,
 * doubling as the admin group-list row since v0.19.0 ({@code kind} and
 * {@code createdAt} added — additive, picker consumers unaffected).
 */
public record AdminGroupOptionResponse(long id, String name, String slug, long memberCount,
        GroupKind kind, Instant createdAt) {
}
