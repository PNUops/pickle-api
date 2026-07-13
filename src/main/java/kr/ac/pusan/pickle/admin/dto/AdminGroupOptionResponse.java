package kr.ac.pusan.pickle.admin.dto;

/** Contract {@code AdminGroupOption}: the announcement screen's group picker. */
public record AdminGroupOptionResponse(long id, String name, String slug, long memberCount) {
}
