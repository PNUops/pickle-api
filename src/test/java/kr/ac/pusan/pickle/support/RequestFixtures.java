package kr.ac.pusan.pickle.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds requests straight into the database, for tests whose subject is
 * something downstream of a request rather than the request flow itself.
 *
 * <p>A request now spans two tables — the common row and the per-type detail —
 * so the pair is written here rather than in every test that needs one. A new
 * resource type adds a method here and nothing anywhere else.
 */
public final class RequestFixtures {

    private RequestFixtures() {
    }

    /** A submitted VM request with the given specification. Returns its id. */
    public static long insertVmRequest(JdbcTemplate jdbc, long workspaceId, long orgId,
            long requesterId, String purpose, Long imageId, int vcpu, int memoryMb, int diskGb) {
        Long resolvedImageId = imageId != null ? imageId
                : jdbc.queryForObject("select min(id) from os_images", Long.class);
        // display_name is mandatory on a request. The purpose stands in for it
        // here for the same reason the backfill used it: it is what the fixture
        // already says the request is for, so no test has to invent a name.
        long requestId = jdbc.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id, purpose,
                                      display_name)
                values ('VM', ?, ?, ?, ?, left(?, 100))
                returning id
                """, Long.class, workspaceId, orgId, requesterId, purpose, purpose);
        jdbc.update("""
                insert into vm_request_details (request_id, image_id, req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, ?, ?)
                """, requestId, resolvedImageId, vcpu, memoryMb, diskGb);
        return requestId;
    }

    /** A submitted VM request carrying a requested end date. */
    public static long insertVmRequest(JdbcTemplate jdbc, long workspaceId, long orgId,
            long requesterId, String purpose, Long imageId, int vcpu, int memoryMb, int diskGb,
            String endDate) {
        long requestId = insertVmRequest(jdbc, workspaceId, orgId, requesterId, purpose, imageId,
                vcpu, memoryMb, diskGb);
        jdbc.update("update requests set req_end_date = cast(? as date) where id = ?",
                endDate, requestId);
        return requestId;
    }

    /** A VM request already in a given status, for queue and summary fixtures. */
    public static long insertVmRequestWithStatus(JdbcTemplate jdbc, long workspaceId, long orgId,
            long requesterId, String purpose, Long imageId, String status) {
        long requestId = insertVmRequest(jdbc, workspaceId, orgId, requesterId, purpose, imageId);
        jdbc.update("update requests set status = ?::request_status where id = ?", status, requestId);
        return requestId;
    }

    /**
     * Marks a request approved: the decision row plus the granted specification
     * on its VM detail row, which is where the specification now lives.
     */
    public static void approveVmRequest(JdbcTemplate jdbc, long requestId, long reviewerId,
            Long imageId, int vcpu, int memoryMb, int diskGb) {
        Long resolvedImageId = imageId != null ? imageId
                : jdbc.queryForObject("select min(id) from os_images", Long.class);
        // The granted specification goes in first: each statement here commits on
        // its own, and the deferred trigger that guards "approved implies granted"
        // fires at the end of whichever one writes the review.
        jdbc.update("""
                update vm_request_details
                   set granted_vcpu = ?, granted_memory_mb = ?, granted_disk_gb = ?,
                       granted_image_id = ?
                 where request_id = ?
                """, vcpu, memoryMb, diskGb, resolvedImageId, requestId);
        jdbc.update("""
                insert into request_reviews (request_id, reviewer_id, decision)
                values (?, ?, 'APPROVE'::review_decision)
                """, requestId, reviewerId);
    }

    /** The same, with the platform's usual small specification. */
    public static long insertVmRequest(JdbcTemplate jdbc, long workspaceId, long orgId,
            long requesterId, String purpose, Long imageId) {
        return insertVmRequest(jdbc, workspaceId, orgId, requesterId, purpose, imageId, 2, 2048, 10);
    }

    /**
     * A submitted LLM API key request, for tests whose subject is the key that
     * an approval would create rather than the request flow. The detail row is
     * written alongside because a request of this type always carries one; the
     * granted columns stay null, which is what "not yet reviewed" looks like.
     */
    public static long insertLlmKeyRequest(JdbcTemplate jdbc, long workspaceId, long orgId,
            long requesterId, String purpose) {
        long requestId = jdbc.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id, purpose,
                                      display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, left(?, 100))
                returning id
                """, Long.class, workspaceId, orgId, requesterId, purpose, purpose);
        jdbc.update("""
                insert into llm_key_request_details (request_id, req_purpose)
                values (?, ?)
                """, requestId, purpose);
        return requestId;
    }
}
