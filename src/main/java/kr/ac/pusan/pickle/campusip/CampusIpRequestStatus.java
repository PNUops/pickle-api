package kr.ac.pusan.pickle.campusip;

/**
 * 교내 IP request lifecycle, all platform-side: REQUESTED (신청) → APPROVED
 * (플랫폼 관리자 승인) → GRANTED (교내 IP 연결 완료, 주소 기록) → REVOKED,
 * plus REQUESTED → REJECTED. REQUESTED/APPROVED/GRANTED count as live — at
 * most one live request per VM (partial unique index).
 */
public enum CampusIpRequestStatus {
    REQUESTED,
    APPROVED,
    GRANTED,
    REJECTED,
    REVOKED
}
