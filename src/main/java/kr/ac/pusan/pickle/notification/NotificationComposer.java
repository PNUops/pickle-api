package kr.ac.pusan.pickle.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.ac.pusan.pickle.access.ResourceType;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Renders the Korean title/body/linkPath for every {@link NotificationEvent},
 * including the two deletion-flow policy notices users must not miss (no
 * backups, admin-only cancellation).
 *
 * <p>Payloads carry whitelisted display fields only — never tokens, passwords
 * or internal identifiers beyond the linked resource ids.</p>
 */
@Component
public class NotificationComposer {

    /** Rendered notification content; {@code eventId} may be per-stage
     *  (e.g. {@code vm.expiry.d7}) and importance may deviate from the
     *  catalog default (D-1 → HIGH). */
    public record Composed(String eventId, String title, String body, String linkPath,
            NotificationImportance importance, Map<String, Object> payload) {
    }

    static final String BACKUP_NOTICE =
            "플랫폼은 VM 데이터를 백업하지 않아요. 삭제한 데이터는 복구할 수 없어요. "
                    + "필요한 데이터는 파기 전에 직접 백업해 주세요.";

    static final String CANCEL_POLICY_NOTICE =
            "삭제는 관리자만 취소할 수 있어요. 관리자에게 문의해 주세요.";

    private static final DateTimeFormatter KST =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    /** SSH gateway host advertised in the VM-created mail (config-driven). */
    private final String sshHost;

    public NotificationComposer(
            @Value("${pickle.ssh.advertised-host:ssh.pcl.kr}") String sshHost) {
        this.sshHost = sshHost == null || sshHost.isBlank() ? "ssh.pcl.kr" : sshHost;
    }

    public Composed compose(NotificationEvent event, Map<String, Object> args) {
        return switch (event) {
            case LLM_USAGE_THRESHOLD -> usageThreshold((java.util.UUID) args.get("keyId"), str(args, "keyName"),
                    (kr.ac.pusan.pickle.llm.alert.UsageAlertFacts) args.get("facts"), (Instant) args.get("evaluatedAt"));
            case GPU_UPDATE -> new Composed(event.id(), str(args, "title"), str(args, "message"),
                    "/console/gpus/" + args.get("allocationId"), event.defaultImportance(), payload(args, "allocationId"));
            case GPU_REVIEW -> new Composed(event.id(), "GPU 관리자 확인", str(args, "message"),
                    "/admin/gpus", event.defaultImportance(), Map.of());
            case REQUEST_SUBMITTED -> requestSubmitted(event, args);
            case REQUEST_APPROVED -> requestApproved(event, args);
            case RESOURCE_GRANTED -> resourceGranted(event, args);
            case REQUEST_REJECTED -> new Composed(event.id(), resourceLabel(args) + " 신청 반려",
                    """
                    %s 신청이 반려됐어요.

                    반려 사유
                    %s""".formatted(resourceLabel(args), str(args, "comment")),
                    "/console/requests/" + args.get("requestId"), event.defaultImportance(),
                    payload(args, "requestId"));
            case VM_CREATE_DONE -> new Composed(event.id(),
                    "VM 생성 완료 — " + str(args, "hostname"),
                    "VM '%s'이 준비됐어요. 상세 화면에서 접속 방법을 확인해 주세요."
                            .formatted(oneLine(str(args, "hostname"))),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "hostname"));
            case VM_CREATE_FAILED -> new Composed(event.id(),
                    "VM 생성 실패 — " + str(args, "hostname"),
                    """
                    VM '%s' 생성에 실패했어요.

                    - 상세: %s

                    관리자에게 문의해 주세요.""".formatted(
                            str(args, "hostname"), oneLine(str(args, "reason"))),
                    Boolean.TRUE.equals(args.get("admin"))
                            ? "/admin/vms" : "/console/vms/" + args.get("vmId"),
                    event.defaultImportance(), payload(args, "vmId", "hostname"));
            case VM_DELETE_ACCEPTED -> new Composed(event.id(),
                    "VM 삭제 접수 안내 — " + str(args, "vmName"),
                    """
                    VM '%s'의 삭제 요청이 접수됐어요.
                    VM은 곧 종료되고 %s (KST) 이후 완전히 삭제될 예정이에요.

                    - %s
                    - %s""".formatted(str(args, "vmName"), KST.format(instant(args, "scheduledFor")),
                            CANCEL_POLICY_NOTICE, BACKUP_NOTICE),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName"));
            case VM_DELETE_SCHEDULED -> new Composed(event.id(),
                    "VM 관리자 삭제 안내 — " + str(args, "vmName"),
                    """
                    관리자가 VM '%s'의 삭제를 예약했어요.

                    - 사유: %s
                    - 파기 예정 시각: %s (KST)

                    - %s
                    - %s""".formatted(str(args, "vmName"), oneLine(str(args, "reason")),
                            KST.format(instant(args, "scheduledFor")),
                            CANCEL_POLICY_NOTICE, BACKUP_NOTICE),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName"));
            case VM_DELETE_CANCELED -> new Composed(event.id(),
                    "VM 삭제 취소 안내 — " + str(args, "vmName"),
                    """
                    관리자가 VM '%s'의 삭제를 취소했어요.
                    VM과 데이터는 유지돼요. 삭제 요청으로 종료된 VM은 콘솔에서 직접 시작해 주세요.""".formatted(
                            str(args, "vmName")),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName"));
            // User-facing wording stays on the "who deleted" axis: an
            // admin-initiated deletion is announced as 관리자 삭제 without
            // exposing the force/immediacy distinction.
            case VM_DELETE_FORCE -> new Composed(event.id(),
                    "VM 관리자 삭제 통지 — " + str(args, "vmName"),
                    """
                    관리자가 VM '%s'의 삭제를 요청했어요.
                    삭제는 취소할 수 없고, 삭제한 데이터는 복구할 수 없어요.

                    관리자에게 문의해 주세요.""".formatted(str(args, "vmName")),
                    null, event.defaultImportance(), payload(args, "vmId", "vmName"));
            case VM_DELETE_COMPLETED -> new Composed(event.id(),
                    "VM 파기 완료 안내 — " + str(args, "vmName"),
                    """
                    VM '%s'의 삭제가 완료됐어요.
                    할당된 자원은 회수됐고 데이터는 복구할 수 없어요.""".formatted(
                            str(args, "vmName")),
                    null, event.defaultImportance(), payload(args, "vmId", "vmName"));
            case DOMAIN_CONNECT_DONE -> new Composed(event.id(), "도메인 연결 완료",
                    "도메인 '%s' 연결이 완료됐어요. HTTPS로 접속할 수 있어요.".formatted(
                            str(args, "fqdn")),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "fqdn"));
            case DOMAIN_CONNECT_FAILED -> new Composed(event.id(), "도메인 연결 실패",
                    """
                    도메인 '%s' 연결에 실패했어요.

                    - 상세: %s

                    설정을 확인한 뒤 콘솔에서 검증을 다시 실행해 주세요.""".formatted(
                            str(args, "fqdn"), oneLine(str(args, "reason"))),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "fqdn"));
            case DOMAIN_RESERVE_EXPIRING -> new Composed(event.id(),
                    "도메인 이름 예약 만료 예정 — " + str(args, "fqdn"),
                    """
                    해제한 %s '%s'의 이름 예약이 %s에 만료돼요.
                    만료 후에는 다른 사용자가 이 이름을 사용할 수 있어요.
                    계속 사용하려면 만료 전에 콘솔에서 %s 주세요.""".formatted(
                            domainNoun(args), str(args, "fqdn"),
                            KST.format(instant(args, "reservedUntil")), domainReclaimVerb(args)),
                    domainLink(args), event.defaultImportance(),
                    payload(args, "vmId", "domainId", "fqdn", "reservedUntil"));
            case DOMAIN_RENEWAL_DUE -> new Composed(event.id(),
                    "도메인 사용 연장 안내 — " + str(args, "fqdn"),
                    """
                    '%s'의 사용 기한이 %s에 끝나요.
                    계속 사용하려면 콘솔에서 연장해 주세요. 기한이 지나면 레코드가 삭제되고 이름은 예약 상태로 바뀌어요.""".formatted(
                            str(args, "fqdn"), KST.format(instant(args, "renewDueAt"))),
                    domainLink(args), event.defaultImportance(),
                    payload(args, "domainId", "fqdn", "renewDueAt"));
            case DOMAIN_RENEWAL_LAPSED -> new Composed(event.id(),
                    "도메인 사용 기한 만료 — " + str(args, "fqdn"),
                    """
                    '%s'의 사용 기한이 지나 레코드가 삭제되고 이름은 예약 상태로 바뀌었어요.
                    %s까지 도메인 상세에서 되살릴 수 있어요. 그 뒤에는 다른 사용자가 이 이름을 사용할 수 있어요."""
                            .formatted(str(args, "fqdn"),
                                    KST.format(instant(args, "reservedUntil"))),
                    domainLink(args), event.defaultImportance(),
                    payload(args, "domainId", "fqdn", "reservedUntil"));
            // Same wording axis as VM_DELETE_FORCE: announced as 관리자 해제
            // without exposing the force/immediacy distinction.
            case DOMAIN_ADMIN_RELEASED -> new Composed(event.id(),
                    "도메인 관리자 해제 — " + str(args, "fqdn"),
                    adminReleasedBody(args),
                    domainLink(args), event.defaultImportance(),
                    payload(args, "vmId", "vmName", "domainId", "fqdn"));
            case DOMAIN_RESERVE_RELEASED -> new Composed(event.id(),
                    "도메인 이름 예약 만료 — " + str(args, "fqdn"),
                    """
                    해제한 %s '%s'의 이름 예약이 만료됐어요.
                    이제 다른 사용자가 이 이름을 사용할 수 있어요.""".formatted(
                            domainNoun(args), str(args, "fqdn")),
                    domainLink(args), event.defaultImportance(),
                    payload(args, "vmId", "domainId", "fqdn"));
            case CERT_FAILURE -> new Composed(event.id(),
                    "인증서 발급 실패 — " + str(args, "fqdn"),
                    """
                    도메인 '%s'의 TLS 인증서를 발급하거나 갱신하지 못했어요.

                    - 상세: %s

                    프록시 에이전트와 DNS 상태를 확인해 주세요.""".formatted(
                            str(args, "fqdn"), oneLine(str(args, "reason"))),
                    "/admin/domains", event.defaultImportance(), payload(args, "fqdn"));
            case VM_EXPIRY_NOTICE -> expiryNotice(args);
            case VM_EXPIRY_STOPPED -> new Composed(event.id(),
                    "VM 사용 기간 만료 — " + str(args, "vmName"),
                    """
                    VM '%s'의 사용 기간이 %s에 끝나 자동 정지됐어요.
                    계속 사용하려면 관리자에게 기간 연장을 요청해 주세요.""".formatted(
                            str(args, "vmName"), args.get("endDate")),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName", "endDate"));
            case ANNOUNCEMENT -> new Composed(event.id(), str(args, "title"), str(args, "body"),
                    null, event.defaultImportance(), null);
            // account-lifecycle events — templates registered with the enum
            // at kickoff (the compose switch is exhaustive by design; a new
            // event without a template must fail the build, not runtime).
            case ACCOUNT_PASSWORD_CHANGED -> new Composed(event.id(), "계정 비밀번호 변경 안내",
                    """
                    비밀번호가 변경됐어요. 다른 로그인 세션은 모두 종료됐어요.

                    본인이 변경한 것이 아니라면 즉시 비밀번호를 재설정하고 관리자에게 문의해 주세요.""",
                    "/console/account", event.defaultImportance(), null);
            case ACCOUNT_IDENTITY_LINKED -> new Composed(event.id(), "구글 계정 연동 안내",
                    """
                    구글 계정이 연동됐어요. 구글 계정으로도 로그인할 수 있어요.

                    본인이 연동한 것이 아니라면 계정 설정에서 연동을 해제하고 관리자에게 문의해 주세요."""
                    + (Boolean.TRUE.equals(args.get("passwordCleared")) ? """


                    비밀번호로 로그인하려면 '비밀번호 찾기'에서 새로 설정해 주세요.""" : ""),
                    "/console/account", event.defaultImportance(), null);
            case ACCOUNT_DISABLED -> new Composed(event.id(), "계정 비활성화 안내",
                    """
                    관리자가 계정을 비활성화했어요. 로그인과 SSH 접속이 차단됐어요.

                    - 사유: %s

                    관리자에게 문의해 주세요.""".formatted(
                            oneLine(str(args, "reason"))),
                    null, event.defaultImportance(), payload(args, "userId", "userEmail"));
            case ACCOUNT_ENABLED -> new Composed(event.id(), "계정 활성화 안내",
                    """
                    계정이 다시 활성화됐어요. 로그인해 이용할 수 있어요.""",
                    null, event.defaultImportance(), payload(args, "userId", "userEmail"));
            case ACCOUNT_WITHDRAWN -> new Composed(event.id(), "회원 탈퇴 완료 안내",
                    """
                    회원 탈퇴가 완료됐어요. 같은 이메일로는 다시 가입할 수 없어요. 계정 정보는 관련 법령과 개인정보처리방침에 따라 보존돼요.""",
                    null, event.defaultImportance(), payload(args, "userId", "userEmail"));
            case ACCOUNT_MFA_ENROLLED -> new Composed(event.id(), "2단계 인증 등록 안내",
                    """
                    2단계 인증이 등록됐어요. 로그인할 때 인증 앱의 코드가 필요해요.

                    본인이 등록한 것이 아니라면 즉시 비밀번호를 변경하고 관리자에게 문의해 주세요.""",
                    "/console/account", event.defaultImportance(), null);
            case ACCOUNT_MFA_DISABLED -> new Composed(event.id(), "2단계 인증 해제 안내",
                    """
                    2단계 인증이 해제됐어요. 비밀번호만으로 로그인할 수 있어요.

                    본인이 해제한 것이 아니라면 즉시 비밀번호를 변경하고 관리자에게 문의해 주세요.""",
                    "/console/account", event.defaultImportance(), null);
            // 직책·학번·소속 are write-once for the holder, so an administrator
            // is the only one who can have changed them and the holder cannot
            // change them back. Told rather than left to notice.
            case ACCOUNT_PROFILE_UPDATED -> new Composed(event.id(), "프로필 정보 변경 안내 (관리자 조치)",
                    """
                    관리자가 직책, 학번, 소속 정보를 변경했어요. 계정 화면에서 확인해 주세요.

                    요청한 적이 없다면 즉시 관리자에게 문의해 주세요.""",
                    "/console/account", event.defaultImportance(), null);
            case ACCOUNT_MFA_RESET -> new Composed(event.id(), "2단계 인증 초기화 안내 (관리자 조치)",
                    """
                    관리자가 2단계 인증을 초기화했어요. 비밀번호로 로그인한 뒤 콘솔에서 2단계 인증을 다시 등록해 주세요.

                    요청한 적이 없다면 즉시 관리자에게 문의해 주세요.""",
                    "/console/account", event.defaultImportance(), null);
            // Relay port forwarding + 교내 IP (contract v0.27.0). The relay
            // observability events target sysadmins; the campus-IP status
            // event targets the requester.
            case RELAY_CONTACT_LOST -> new Composed(event.id(),
                    "릴레이 접촉 두절 — " + str(args, "relayName"),
                    """
                    릴레이 '%s'의 동기화가 끊겼어요.
                    마지막 접촉: %s

                    릴레이 인스턴스와 터널 상태를 확인해 주세요. 기존 포워딩 규칙은 유지돼요.""".formatted(
                            str(args, "relayName"), kstOrUnknown(args, "lastContactAt")),
                    "/admin/network", event.defaultImportance(),
                    payload(args, "relayId", "relayName"));
            case RELAY_NEVER_CONTACTED -> new Composed(event.id(),
                    "릴레이 미접속 — " + str(args, "relayName"),
                    Boolean.TRUE.equals(args.get("tokenIssued"))
                            ? """
                            릴레이 '%s'의 동기화가 아직 없어요.
                            에이전트 설치 상태와 설정된 토큰, 터널 상태를 확인해 주세요.
                            동기화 전에는 포워딩 규칙이 적용되지 않아요.""".formatted(
                                    str(args, "relayName"))
                            : """
                            릴레이 '%s'의 sync 토큰이 없어 포워딩 규칙을 적용하지 못하고 있어요.
                            콘솔에서 토큰을 발급하고 에이전트에 설정해 주세요.""".formatted(
                                    str(args, "relayName")),
                    "/admin/network", event.defaultImportance(),
                    payload(args, "relayId", "relayName"));
            case RELAY_BAND_USAGE_HIGH -> new Composed(event.id(),
                    "릴레이 포트 대역 사용률 경고 — " + str(args, "relayName"),
                    """
                    릴레이 '%s'의 공개 포트 사용률이 %s%%로 경고 기준 %s%%에 도달했어요. 포트 대역을 늘리거나 사용하지 않는 포워딩을 정리해 주세요.""".formatted(
                            str(args, "relayName"), str(args, "usagePercent"),
                            str(args, "thresholdPercent")),
                    "/admin/network", event.defaultImportance(),
                    payload(args, "relayId", "relayName", "usagePercent"));
            case PORT_MAPPING_SUSPENDED -> new Composed(event.id(),
                    "포트포워딩 정지: " + str(args, "vmName"),
                    """
                    VM '%s'의 포트포워딩(%s %s) 정지 요청을 접수했어요.

                    - 사유: %s

                    관리자에게 문의해 주세요.""".formatted(str(args, "vmName"),
                            str(args, "proto"), str(args, "publicPort"), oneLine(str(args, "reason"))),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName", "proto", "publicPort"));
            case PORT_MAPPING_DELETED -> new Composed(event.id(),
                    "포트포워딩 삭제: " + str(args, "vmName"),
                    """
                    관리자가 VM '%s'의 포트포워딩(%s %s) 삭제를 요청했어요. 외부 접속이 필요하면 포트포워딩을 다시 만들어 주세요.

                    관리자에게 문의해 주세요.""".formatted(str(args, "vmName"),
                            str(args, "proto"), str(args, "publicPort")),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName", "proto", "publicPort"));
            case CAMPUS_IP_REQUESTED -> new Composed(event.id(),
                    "교내 IP 신청 접수 — " + str(args, "vmName"),
                    """
                    VM '%s'의 교내 IP 신청이 접수됐어요. 검토해 주세요.

                    - 신청 목적: %s""".formatted(str(args, "vmName"), oneLine(str(args, "purpose"))),
                    "/admin/network", event.defaultImportance(),
                    payload(args, "requestId", "vmId", "vmName"));
            case CAMPUS_IP_STATUS_CHANGED -> new Composed(event.id(),
                    "교내 IP 신청 상태 변경 — " + str(args, "vmName"),
                    """
                    VM '%s'의 교내 IP 신청이 %s 상태로 바뀌었어요.%s%s""".formatted(
                            str(args, "vmName"), str(args, "statusLabel"),
                            args.get("grantedAddress") != null
                                    ? "\n\n- 할당 주소: " + str(args, "grantedAddress") : "",
                            // The blank line opens the list block, so it
                            // belongs to whichever item comes first: without it
                            // a note-only notice runs its list line straight on
                            // from the sentence.
                            args.get("adminNote") != null
                                    ? (args.get("grantedAddress") != null ? "\n" : "\n\n")
                                            + "- 관리자 메모: " + oneLine(str(args, "adminNote"))
                                    : ""),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "requestId", "vmId", "vmName"));
            case WORKSPACE_DELETED -> new Composed(event.id(),
                    "워크스페이스 삭제 안내 — " + oneLine(str(args, "workspaceName")),
                    "'%s' 워크스페이스가 삭제됐어요.".formatted(oneLine(str(args, "workspaceName"))),
                    null, event.defaultImportance(), payload(args, "workspaceId", "workspaceName"));
        };
    }

    /** A short threshold event; authorization identity remains in the existing payload. */
    public Composed usageThreshold(java.util.UUID keyId, String keyName,
            kr.ac.pusan.pickle.llm.alert.UsageAlertFacts facts, Instant evaluatedAt) {
        String limit = facts.axis() == kr.ac.pusan.pickle.llm.alert.UsageAlertAxis.TOKEN
                ? "일일 토큰 한도" : switch (facts.period()) {
                    case DAILY -> "일일 금액 한도";
                    case WEEKLY -> "주간 금액 한도";
                    case MONTHLY -> "월간 금액 한도";
                    case TOTAL -> "총액 한도";
                };
        String body = oneLine(keyName) + "의 " + limit + "를 "
                + (facts.stage() == 100 ? "모두 사용했어요." : "80% 이상 사용했어요.");
        return new Composed(NotificationEvent.LLM_USAGE_THRESHOLD.id(),
                "키 사용량 알림", body,
                "/console/llm-keys/" + keyId, NotificationImportance.NORMAL,
                Map.of("keyId", keyId, "axis", facts.axis().name()));
    }

    /**
     * The approval notice. What it may say depends on what happens next, and
     * that differs per resource type: a VM is provisioned by the platform and
     * gets its own completion notice later, while an LLM key and a domain are
     * already created by the time this is composed and the next move is the
     * requester's. The sentence must not promise a follow-up that no code
     * publishes — an unconditional "생성이 완료되면 다시 알려드립니다" was sent
     * for LLM keys for months, and nothing ever arrived.
     *
     * <p>Switched over {@link ResourceType} rather than compared against a
     * string, so a fifth kind fails the build here instead of silently
     * inheriting the VM sentence, which is how the LLM key came to have it.</p>
     */
    private Composed requestApproved(NotificationEvent event, Map<String, Object> args) {
        ResourceType type = resourceType(args);
        String label = resourceLabel(args);
        // The requester names their own resource, and a name carrying a newline
        // would both split the sentence and, if the next line began with "- ",
        // be promoted into a list item — letting a requester forge a 검토 의견
        // bullet in a mail the platform sends.
        String name = oneLine(str(args, "resourceName"));
        // An expression switch with no default: a fifth resource type fails to
        // compile here. A statement switch would not, and neither did the
        // string comparison this replaces.
        String body = type == null
                ? args.get("recipientTotal") != null
                        ? "%s '%s' 신청이 승인됐어요.\n%s".formatted(label, name, recipientSummary(args))
                        : "%s '%s' 신청이 승인됐어요.\n콘솔에서 확인해 주세요.".formatted(label, name)
                : switch (type) {
                    case VM -> """
                            VM '%s' 신청이 승인됐어요.
                            VM이 준비되면 알려드릴게요.""".formatted(name);
                    // The key row exists already; it lands PENDING and only its
                    // owner can mint the secret. No warning about the one-time
                    // reveal here — the issue screen carries that at the moment
                    // it applies, and this mail arrives days earlier.
                    case LLM_API_KEY -> """
                            LLM API 키 '%s' 신청이 승인됐어요.
                            콘솔에서 키를 발급해 사용해 주세요.""".formatted(name);
                    // Issued inside the approval transaction with no
                    // after-commit work, the same shape as the key: nothing is
                    // starting, and the next move is the requester's.
                    case DOMAIN -> """
                            도메인 '%s' 신청이 승인됐어요.
                            콘솔에서 레코드를 추가해 사용해 주세요.""".formatted(name);
                    // The only kind that really is queued: the card arrives when
                    // one frees up, and the scheduler publishes its own notice.
                    case GPU -> """
                            GPU 신청이 승인됐어요. 대기 순서는 %s번째예요.
                            GPU가 할당되면 알려드릴게요.""".formatted(
                                    str(args, "queuePosition"));
                };
        // Where the next move is. For a key and a name that is the resource's
        // own screen rather than the request: the request is finished and what
        // the reader has to do — mint the secret, add the records — is over
        // there. Everything else keeps pointing at the request, which is where
        // its story continues.
        String link;
        if (type == ResourceType.LLM_API_KEY && args.get("llmKeyId") != null) {
            link = "/console/llm-keys/" + args.get("llmKeyId");
        } else if (type == ResourceType.DOMAIN && args.get("domainId") != null) {
            link = "/console/domains/" + args.get("domainId");
        } else {
            link = "/console/requests/" + args.get("requestId");
        }
        return new Composed(event.id(), label + " 신청 승인", body + reviewComment(args),
                link, event.defaultImportance(),
                payload(args, "requestId", "resourceName"));
    }

    /**
     * What happened to the recipients of a many-person request, for the
     * person who asked. Parts that are zero are left out, so a request whose
     * recipients all hold accounts does not mention joining.
     */
    private static String recipientSummary(Map<String, Object> args) {
        int total = count(args, "recipientTotal");
        int queued = count(args, "queued");
        int pendingJoin = count(args, "pendingJoin");
        int skipped = count(args, "skipped");
        // Worded about the resources, not the people: "M명은 생성을 시작했습니다"
        // read as if the recipients themselves had started something.
        String started = queued > 0 ? queued + "명의 리소스는 생성 대기열에 들어갔" : null;
        String waiting = pendingJoin > 0 ? pendingJoin + "명은 가입 대기 상태예요." : null;
        StringBuilder sentence = new StringBuilder("대상자 " + total + "명 중 ");
        if (started != null && waiting != null) {
            sentence.append(started).append("고 ").append(waiting);
        } else if (started != null) {
            sentence.append(started).append("어요.");
        } else if (waiting != null) {
            sentence.append(waiting);
        }
        if (skipped > 0) {
            if (started != null || waiting != null) {
                sentence.append(' ');
            }
            sentence.append(skipped).append("명은 대상에서 제외됐어요.");
        }
        return sentence.toString();
    }

    private static int count(Map<String, Object> args, String key) {
        return args.get(key) instanceof Number n ? n.intValue() : 0;
    }

    /**
     * The notice for someone a resource was made for on another person's
     * request. They never applied, so the approval wording ("신청이
     * 승인되었습니다") would tell them something untrue; this names the
     * workspace instead, which is how they know where it came from.
     *
     * <p>Only keys reach this today: a VM recipient hears from
     * {@code vm.create.done} once provisioning finishes, and the materializer
     * publishes nothing for it at creation. The VM branch exists so the
     * switch stays exhaustive and a later caller gets a true sentence.</p>
     */
    private Composed resourceGranted(NotificationEvent event, Map<String, Object> args) {
        ResourceType type = resourceType(args);
        if (type == null) {
            throw new IllegalStateException("resource.granted needs a resource type");
        }
        // Both names are chosen by people other than the reader, and a
        // newline in either would split the sentence or start a list item.
        String workspace = oneLine(str(args, "workspaceName"));
        String name = oneLine(str(args, "resourceName"));
        return switch (type) {
            case LLM_API_KEY -> new Composed(event.id(), "LLM API 키 지급",
                    """
                    '%s' 워크스페이스에서 '%s' LLM API 키를 지급받았어요.
                    콘솔에서 키를 발급해 사용해 주세요.""".formatted(workspace, name),
                    args.get("llmKeyId") != null ? "/console/llm-keys/" + args.get("llmKeyId") : "/console/llm-keys",
                    event.defaultImportance(),
                    payload(args, "requestId", "workspaceName", "resourceName", "llmKeyId"));
            case VM -> new Composed(event.id(), "VM 지급",
                    """
                    '%s' 워크스페이스에서 '%s' VM을 지급받았어요.
                    콘솔에서 생성 진행 상황을 확인해 주세요.""".formatted(workspace, name),
                    args.get("vmId") != null ? "/console/vms/" + args.get("vmId") : "/console/vms",
                    event.defaultImportance(),
                    payload(args, "requestId", "workspaceName", "resourceName", "vmId"));
            // Neither kind can name recipients, so nothing is ever granted to
            // someone else through a request of theirs.
            case DOMAIN, GPU -> throw new IllegalStateException(
                    "resource.granted does not apply to " + type);
        };
    }

    /** The reviewer's comment as its own list item, or nothing. Shared by every
     *  kind — the GPU branch used to drop it, so a GPU reviewer's 검토 의견
     *  never reached the requester. */
    private static String reviewComment(Map<String, Object> args) {
        return args.get("comment") != null
                ? "\n\n검토 의견\n" + str(args, "comment") : "";
    }

    private Composed requestSubmitted(NotificationEvent event, Map<String, Object> args) {
        boolean admin = Boolean.TRUE.equals(args.get("admin"));
        String label = resourceLabel(args);
        String title = admin ? "새 " + label + " 신청 접수" : label + " 신청 접수";
        String body = admin
                ? """
                  워크스페이스 '%s'에서 새 %s 신청이 접수됐어요. 검토해 주세요.

                  신청 목적
                  %s""".formatted(oneLine(str(args, "workspaceName")), label, str(args, "purpose"))
                : "'%s'의 %s 신청이 접수됐어요. 검토가 끝나면 알려드릴게요."
                        .formatted(oneLine(str(args, "workspaceName")), label);
        String link = (admin ? "/admin/requests/" : "/console/requests/") + args.get("requestId");
        return new Composed(event.id(), title, body, link, event.defaultImportance(),
                payload(args, "requestId", "workspaceName"));
    }

    /**
     * The id keeps the stage ladder (vm.expiry.d7 …) the dedup design hangs on;
     * the wording and importance use the real days left, which a late-created
     * VM makes smaller than the stage.
     */
    private Composed expiryNotice(Map<String, Object> args) {
        int stage = ((Number) args.get("days")).intValue();
        int daysLeft = args.get("daysLeft") instanceof Number n ? n.intValue() : stage;
        String vmName = str(args, "vmName");
        String title = daysLeft <= 0
                ? "VM 사용 종료 오늘 — " + vmName
                : "VM 사용 종료 D-" + daysLeft + " — " + vmName;
        String firstLine = daysLeft <= 0
                ? "VM '%s'의 사용 종료일(%s)이 오늘이에요.".formatted(vmName, args.get("endDate"))
                : "VM '%s'의 사용 종료일(%s)까지 %d일 남았어요.".formatted(
                        vmName, args.get("endDate"), daysLeft);
        return new Composed("vm.expiry.d" + stage, title,
                // Two newlines, not one: the text block's leading blank line
                // only looks like a paragraph break in source.
                firstLine + "\n\n"
                        + "종료일이 지나면 VM이 자동 정지될 수 있어요. 계속 사용하려면 관리자에게 기간 연장을 요청해 주세요.",
                "/console/vms/" + args.get("vmId"),
                daysLeft <= 1 ? NotificationImportance.HIGH : NotificationImportance.NORMAL,
                payload(args, "vmId", "vmName", "endDate"));
    }

    /**
     * The word the request-flow notices use for the thing being asked for. The
     * request events are shared by every resource type, so the type travels in
     * the payload and the sentence reads it; a payload without one (or naming a
     * type this build does not know) falls back to the generic word rather than
     * asserting it is a VM.
     */
    private static String resourceLabel(Map<String, Object> args) {
        ResourceType type = resourceType(args);
        return type != null ? type.label() : "리소스";
    }

    /** The payload's resource type, or {@code null} when it is absent or names
     *  a type this build does not know. */
    private static @Nullable ResourceType resourceType(Map<String, Object> args) {
        Object type = args.get("type");
        if (type == null) {
            return null;
        }
        for (ResourceType candidate : ResourceType.values()) {
            if (candidate.name().equals(String.valueOf(type))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Free text on one line. A value carrying its own newline would otherwise
     * terminate the {@code "- "} list run it sits in, dropping the rest into a
     * separate paragraph — visible in the HTML part only, and much more so now
     * that a single newline renders as a line break.
     */
    private static String oneLine(String text) {
        return text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').strip();
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    private static Instant instant(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof LocalDate d) {
            return d.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        }
        if (value instanceof Date d) {
            return d.toInstant(); // java.sql.Timestamp straight off a query row
        }
        if (value instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    /**
     * KST-formatted timestamp for display. A value the composer cannot read
     * must not abort the publish it is embedded in — these notices are written
     * inside the caller's transaction, one row at a time.
     */
    private static String kstOrUnknown(Map<String, Object> args, String key) {
        try {
            return KST.format(instant(args, key));
        } catch (RuntimeException e) {
            return "확인 불가";
        }
    }

    /** Whitelist-copy of the given display fields into the stored payload. */
    /**
     * The admin-release notice. A domain this platform serves is named by its
     * VM, because that is the thing its owner recognises it by; one that only
     * holds records has no VM to name, and a sentence with an empty name in it
     * reads as a bug to the person it reaches.
     */
    private static String adminReleasedBody(Map<String, Object> args) {
        String tail = """
                이름은 예약 없이 회수됐어요. 이 주소로는 더 이상 접속할 수 없어요.

                관리자에게 문의해 주세요.""";
        return args.get("vmId") != null
                ? "VM '%s'에 연결된 '%s' 도메인을 관리자가 해제했어요.\n%s"
                        .formatted(str(args, "vmName"), str(args, "fqdn"), tail)
                : "'%s' 도메인을 관리자가 해제했어요.\n%s"
                        .formatted(str(args, "fqdn"), tail);
    }

    /**
     * What to call the thing in a notice that reaches both kinds. A subdomain
     * this platform serves is published from a VM and is re-attached to one; a
     * name that only holds records is made and remade on its own, so calling
     * it a platform subdomain and telling its owner to "연결" names an action
     * that does not exist for it.
     */
    private static String domainNoun(Map<String, Object> args) {
        return args.get("vmId") != null ? "플랫폼 서브도메인" : "도메인";
    }

    /** And what its owner does to get it back, which differs the same way. */
    private static String domainReclaimVerb(Map<String, Object> args) {
        return args.get("vmId") != null ? "같은 이름으로 다시 연결해" : "도메인을 되살려";
    }

    /**
     * Where a domain notice points. A domain that this platform serves is read
     * on its VM's page, which is where its whole publication lives; one that
     * only holds records has no VM and is its own resource. The link is chosen
     * by which the payload carries rather than by the event, because the same
     * three events now reach both kinds.
     */
    private static String domainLink(Map<String, Object> args) {
        return args.get("vmId") != null
                ? "/console/vms/" + args.get("vmId")
                : "/console/domains/" + args.get("domainId");
    }

    private static Map<String, Object> payload(Map<String, Object> args, String... keys) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String key : keys) {
            if (args.get(key) != null) {
                payload.put(key, args.get(key));
            }
        }
        return payload.isEmpty() ? null : payload;
    }
}
