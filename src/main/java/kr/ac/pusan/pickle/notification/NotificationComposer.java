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
            "플랫폼은 VM 데이터를 백업하지 않으며 삭제 후 복구할 수 없습니다. "
                    + "필요한 데이터는 파기 전에 직접 백업해 주세요.";

    static final String CANCEL_POLICY_NOTICE =
            "삭제 취소는 관리자만 가능합니다. 복구가 필요하면 관리자에게 문의해 주세요.";

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
            case REQUEST_SUBMITTED -> requestSubmitted(event, args);
            case REQUEST_APPROVED -> new Composed(event.id(), resourceLabel(args) + " 신청 승인",
                    """
                    %s 신청이 승인되었습니다. %s '%s' 생성이 시작됩니다.
                    생성이 완료되면 다시 알려드립니다.%s""".formatted(resourceLabel(args),
                            resourceLabel(args), str(args, "resourceName"),
                            args.get("comment") != null
                                    ? "\n\n- 검토 의견: " + str(args, "comment") : ""),
                    "/console/requests/" + args.get("requestId"), event.defaultImportance(),
                    payload(args, "requestId", "resourceName"));
            case REQUEST_REJECTED -> new Composed(event.id(), resourceLabel(args) + " 신청 반려",
                    """
                    %s 신청이 반려되었습니다.

                    - 반려 사유: %s""".formatted(resourceLabel(args), str(args, "comment")),
                    "/console/requests/" + args.get("requestId"), event.defaultImportance(),
                    payload(args, "requestId"));
            case VM_CREATE_DONE -> new Composed(event.id(),
                    "VM 생성 완료 — " + str(args, "hostname"),
                    """
                    신청하신 VM이 생성되어 실행 중입니다.

                    - 호스트명: %s
                    - 내부 IP: %s
                    - SSH 계정: %s
                    - SSH 접속: ssh %s@%s
                    - 비밀번호: 콘솔의 VM 상세 화면에서 확인할 수 있습니다.

                    [안내] SSH 접속에는 이 VM 전용 SSH 키가 필요합니다. VM 상세 화면에서 키를 발급받아 개인키 파일을 내려받은 뒤 ssh -i 로 접속해 주세요.

                    [중요] 플랫폼은 VM 데이터를 백업하지 않습니다(사용자 책임).
                    중요한 데이터는 반드시 직접 백업해 주세요.
                    """.formatted(str(args, "hostname"),
                            args.get("ip") != null ? str(args, "ip") : "(확인 중)",
                            str(args, "sshUsername"), str(args, "hostname"), sshHost),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "hostname"));
            case VM_CREATE_FAILED -> new Composed(event.id(),
                    "VM 생성 실패 — " + str(args, "hostname"),
                    """
                    VM '%s' 생성 중 오류가 발생했습니다.

                    - 상세: %s

                    관리자가 상태를 확인한 뒤 조치합니다. 문의 사항은 관리자에게 연락해 주세요.""".formatted(
                            str(args, "hostname"), str(args, "reason")),
                    Boolean.TRUE.equals(args.get("admin"))
                            ? "/admin/vms" : "/console/vms/" + args.get("vmId"),
                    event.defaultImportance(), payload(args, "vmId", "hostname"));
            case VM_DELETE_ACCEPTED -> new Composed(event.id(),
                    "VM 삭제 접수 안내 — " + str(args, "vmName"),
                    """
                    VM '%s'의 삭제 요청이 접수되었습니다. VM은 곧 종료되며,
                    %s (KST) 이후 완전히 파기될 예정입니다.

                    - %s
                    - %s""".formatted(str(args, "vmName"), KST.format(instant(args, "scheduledFor")),
                            CANCEL_POLICY_NOTICE, BACKUP_NOTICE),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName"));
            case VM_DELETE_SCHEDULED -> new Composed(event.id(),
                    "VM 관리자 삭제 안내 — " + str(args, "vmName"),
                    """
                    관리자가 VM '%s'의 삭제를 접수했습니다.

                    - 사유: %s
                    - 파기 예정 시각: %s (KST)

                    - %s
                    - %s""".formatted(str(args, "vmName"), str(args, "reason"),
                            KST.format(instant(args, "scheduledFor")),
                            CANCEL_POLICY_NOTICE, BACKUP_NOTICE),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName"));
            case VM_DELETE_CANCELED -> new Composed(event.id(),
                    "VM 삭제 취소 안내 — " + str(args, "vmName"),
                    """
                    VM '%s'에 접수되어 있던 삭제가 관리자에 의해 취소되었습니다.
                    VM과 데이터는 그대로 유지됩니다. (본인 삭제로 종료되었던 VM은
                    STOPPED 상태로 남아 있으며, 콘솔에서 직접 시작할 수 있습니다.)""".formatted(
                            str(args, "vmName")),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName"));
            // User-facing wording stays on the "who deleted" axis: an
            // admin-initiated deletion is announced as 관리자 삭제 without
            // exposing the force/immediacy distinction.
            case VM_DELETE_FORCE -> new Composed(event.id(),
                    "VM 관리자 삭제 통지 — " + str(args, "vmName"),
                    """
                    관리자가 VM '%s'를 삭제했습니다. 이 삭제는 취소할 수 없으며,
                    할당되었던 자원은 회수됩니다.

                    - %s

                    문의 사항은 관리자에게 연락해 주세요.""".formatted(str(args, "vmName"), BACKUP_NOTICE),
                    null, event.defaultImportance(), payload(args, "vmId", "vmName"));
            case VM_DELETE_COMPLETED -> new Composed(event.id(),
                    "VM 파기 완료 안내 — " + str(args, "vmName"),
                    """
                    VM '%s'의 유예 기간이 끝나 파기가 완료되었습니다.
                    할당되었던 자원(IP 등)은 회수되었으며, 이 작업은 되돌릴 수 없습니다.""".formatted(
                            str(args, "vmName")),
                    null, event.defaultImportance(), payload(args, "vmId", "vmName"));
            case DOMAIN_CONNECT_DONE -> new Composed(event.id(), "도메인 연결 완료",
                    "도메인 '%s' 연결이 완료되었습니다. 이제 HTTPS로 접속할 수 있습니다.".formatted(
                            str(args, "fqdn")),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "fqdn"));
            case DOMAIN_CONNECT_FAILED -> new Composed(event.id(), "도메인 연결 실패",
                    """
                    도메인 '%s' 연결에 실패했습니다.

                    - 상세: %s

                    설정을 확인한 뒤 콘솔에서 검증을 다시 실행해 주세요.""".formatted(
                            str(args, "fqdn"), str(args, "reason")),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "fqdn"));
            case DOMAIN_RESERVE_EXPIRING -> new Composed(event.id(),
                    "도메인 이름 예약 만료 예정 — " + str(args, "fqdn"),
                    """
                    해제한 플랫폼 서브도메인 '%s'의 이름 예약이 %s에 만료됩니다.
                    만료 후에는 다른 사용자가 이 이름을 사용할 수 있습니다.
                    계속 사용하려면 만료 전에 같은 이름으로 다시 연결해 주세요.""".formatted(
                            str(args, "fqdn"), KST.format(instant(args, "reservedUntil"))),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "fqdn", "reservedUntil"));
            // Same wording axis as VM_DELETE_FORCE: announced as 관리자 해제
            // without exposing the force/immediacy distinction.
            case DOMAIN_ADMIN_RELEASED -> new Composed(event.id(),
                    "도메인 관리자 해제 — " + str(args, "fqdn"),
                    """
                    VM '%s'에 연결된 도메인 '%s'이(가) 관리자에 의해 해제되었습니다.
                    이름은 예약 없이 즉시 회수되었으며, 이 주소로는 더 이상 접속할 수 없습니다.

                    문의 사항은 관리자에게 연락해 주세요.""".formatted(
                            str(args, "vmName"), str(args, "fqdn")),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName", "fqdn"));
            case DOMAIN_RESERVE_RELEASED -> new Composed(event.id(),
                    "도메인 이름 예약 만료 — " + str(args, "fqdn"),
                    """
                    해제한 플랫폼 서브도메인 '%s'의 이름 예약이 만료되어 회수되었습니다.
                    이제 다른 사용자가 이 이름을 사용할 수 있습니다.""".formatted(
                            str(args, "fqdn")),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "fqdn"));
            case CERT_FAILURE -> new Composed(event.id(),
                    "인증서 발급 실패 — " + str(args, "fqdn"),
                    """
                    도메인 '%s'의 TLS 인증서 발급/갱신에 실패했습니다.

                    - 상세: %s

                    프록시 에이전트와 DNS 상태를 확인해 주세요.""".formatted(
                            str(args, "fqdn"), str(args, "reason")),
                    "/admin/domains", event.defaultImportance(), payload(args, "fqdn"));
            case VM_EXPIRY_NOTICE -> expiryNotice(args);
            case VM_EXPIRY_STOPPED -> new Composed(event.id(),
                    "VM 사용 기간 만료 — " + str(args, "vmName"),
                    """
                    VM '%s'의 사용 기간(종료일 %s)이 만료되어 자동 정지되었습니다.
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
                    계정 비밀번호가 방금 변경되었습니다. 기존의 다른 로그인 세션은
                    모두 종료되었습니다.

                    본인이 변경한 것이 아니라면 즉시 비밀번호를 재설정하고
                    관리자에게 문의해 주세요.""",
                    "/console/account", event.defaultImportance(), null);
            case ACCOUNT_IDENTITY_LINKED -> new Composed(event.id(), "구글 계정 연동 안내",
                    """
                    이 계정에 구글 계정이 연동되었습니다. 앞으로 구글 계정으로도
                    로그인할 수 있습니다.

                    본인이 연동한 것이 아니라면 계정 설정에서 연동을 해제하고
                    관리자에게 문의해 주세요."""
                    + (Boolean.TRUE.equals(args.get("passwordCleared")) ? """


                    이 계정은 이메일 인증을 마치지 않은 상태였습니다. 그때 설정돼
                    있던 비밀번호는 소유가 확인되지 않은 값이라 무효화했습니다.
                    비밀번호로도 로그인하려면 비밀번호 재설정으로 새로 설정해
                    주세요.""" : ""),
                    "/console/account", event.defaultImportance(), null);
            case ACCOUNT_DISABLED -> new Composed(event.id(), "계정 비활성화 안내",
                    """
                    관리자에 의해 계정이 비활성화되어 로그인과 SSH 접속이
                    차단되었습니다.

                    - 사유: %s

                    이의가 있거나 문의가 필요하면 관리자에게 연락해 주세요.""".formatted(
                            str(args, "reason")),
                    null, event.defaultImportance(), payload(args, "userId", "userEmail"));
            case ACCOUNT_ENABLED -> new Composed(event.id(), "계정 활성화 안내",
                    """
                    비활성화되었던 계정이 다시 활성화되었습니다.
                    이제 정상적으로 로그인해 이용할 수 있습니다.""",
                    null, event.defaultImportance(), payload(args, "userId", "userEmail"));
            case ACCOUNT_WITHDRAWN -> new Composed(event.id(), "회원 탈퇴 완료 안내",
                    """
                    회원 탈퇴가 완료되었습니다. 계정 정보는 관련 법령과
                    개인정보처리방침에 따라 보존되며, 같은 이메일로는 다시
                    가입할 수 없습니다.

                    그동안 이용해 주셔서 감사합니다.""",
                    null, event.defaultImportance(), payload(args, "userId", "userEmail"));
            case ACCOUNT_MFA_ENROLLED -> new Composed(event.id(), "2단계 인증 등록 안내",
                    """
                    계정에 2단계 인증(TOTP)이 등록되었습니다. 앞으로 로그인할 때
                    인증 앱의 코드가 필요합니다.

                    본인이 등록한 것이 아니라면 즉시 비밀번호를 변경하고
                    관리자에게 문의해 주세요.""",
                    "/console/account", event.defaultImportance(), null);
            case ACCOUNT_MFA_DISABLED -> new Composed(event.id(), "2단계 인증 해제 안내",
                    """
                    계정의 2단계 인증이 해제되었습니다. 이제 비밀번호만으로
                    로그인할 수 있습니다.

                    본인이 해제한 것이 아니라면 즉시 비밀번호를 변경하고
                    관리자에게 문의해 주세요.""",
                    "/console/account", event.defaultImportance(), null);
            case ACCOUNT_MFA_RESET -> new Composed(event.id(), "2단계 인증 초기화 안내 (관리자 조치)",
                    """
                    관리자가 계정의 2단계 인증을 초기화했습니다. 다음 로그인은
                    비밀번호만으로 가능하며, 보안을 위해 콘솔에서 2단계 인증을
                    다시 등록해 주세요.

                    요청한 적이 없다면 즉시 관리자에게 문의해 주세요.""",
                    "/console/account", event.defaultImportance(), null);
            // Relay port forwarding + 교내 IP (contract v0.27.0). The relay
            // observability events target sysadmins; the campus-IP status
            // event targets the requester.
            case RELAY_CONTACT_LOST -> new Composed(event.id(),
                    "릴레이 접촉 두절 — " + str(args, "relayName"),
                    """
                    릴레이 '%s'의 에이전트가 예상 주기 안에 동기화하지 않았습니다.
                    마지막 접촉: %s

                    릴레이 인스턴스와 터널 상태를 확인해 주세요. 마지막으로 적용된
                    포워딩 규칙은 릴레이에 그대로 남아 있습니다.""".formatted(
                            str(args, "relayName"), kstOrUnknown(args, "lastContactAt")),
                    "/admin/network", event.defaultImportance(),
                    payload(args, "relayId", "relayName"));
            case RELAY_NEVER_CONTACTED -> new Composed(event.id(),
                    "릴레이 미접속 — " + str(args, "relayName"),
                    Boolean.TRUE.equals(args.get("tokenIssued"))
                            ? """
                            릴레이 '%s'의 에이전트가 지금까지 한 번도 동기화하지 않았습니다.
                            에이전트 설치 상태와 설정된 토큰, 터널 상태를 확인해 주세요.
                            동기화가 없는 동안 포워딩 규칙은 릴레이에 적용되지 않습니다.""".formatted(
                                    str(args, "relayName"))
                            : """
                            릴레이 '%s'에 sync 토큰이 발급되지 않은 상태가 계속되고 있습니다.
                            토큰이 없으면 에이전트는 인증에 실패해 포워딩 규칙을 받지 못합니다.
                            콘솔에서 토큰을 발급하고 에이전트에 설정해 주세요.""".formatted(
                                    str(args, "relayName")),
                    "/admin/network", event.defaultImportance(),
                    payload(args, "relayId", "relayName"));
            case RELAY_BAND_USAGE_HIGH -> new Composed(event.id(),
                    "릴레이 포트 대역 사용률 경고 — " + str(args, "relayName"),
                    """
                    릴레이 '%s'의 공개 포트 대역 사용률이 %s%%에 도달했습니다
                    (임계값 %s%%). 대역 확장 또는 정리가 필요할 수 있습니다.""".formatted(
                            str(args, "relayName"), str(args, "usagePercent"),
                            str(args, "thresholdPercent")),
                    "/admin/network", event.defaultImportance(),
                    payload(args, "relayId", "relayName", "usagePercent"));
            case PORT_MAPPING_SUSPENDED -> new Composed(event.id(),
                    "포트 포워딩 정지 — " + str(args, "vmName"),
                    """
                    VM '%s'의 포트 포워딩(%s %s)이 정지되었습니다.

                    - 사유: %s

                    문의 사항은 관리자에게 연락해 주세요.""".formatted(str(args, "vmName"),
                            str(args, "proto"), str(args, "publicPort"), str(args, "reason")),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName", "proto", "publicPort"));
            case PORT_MAPPING_DELETED -> new Composed(event.id(),
                    "포트 포워딩 삭제 — " + str(args, "vmName"),
                    """
                    VM '%s'의 포트 포워딩(%s %s)이 관리자에 의해 삭제되었습니다.
                    외부 접속 경로가 제거되었으며, 필요하면 새 포워딩을 다시 신청할 수 있습니다.

                    문의 사항은 관리자에게 연락해 주세요.""".formatted(str(args, "vmName"),
                            str(args, "proto"), str(args, "publicPort")),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "vmId", "vmName", "proto", "publicPort"));
            case CAMPUS_IP_REQUESTED -> new Composed(event.id(),
                    "교내 IP 신청 접수 — " + str(args, "vmName"),
                    """
                    VM '%s'에 대한 교내 IP 신청이 접수되었습니다. 검토해 주세요.

                    - 신청 목적: %s""".formatted(str(args, "vmName"), str(args, "purpose")),
                    "/admin/network", event.defaultImportance(),
                    payload(args, "requestId", "vmId", "vmName"));
            case CAMPUS_IP_STATUS_CHANGED -> new Composed(event.id(),
                    "교내 IP 신청 상태 변경 — " + str(args, "vmName"),
                    """
                    VM '%s'의 교내 IP 신청 상태가 '%s'(으)로 변경되었습니다.%s%s""".formatted(
                            str(args, "vmName"), str(args, "statusLabel"),
                            args.get("grantedAddress") != null
                                    ? "\n\n- 할당 주소: " + str(args, "grantedAddress") : "",
                            args.get("adminNote") != null
                                    ? "\n- 관리자 메모: " + str(args, "adminNote") : ""),
                    "/console/vms/" + args.get("vmId"), event.defaultImportance(),
                    payload(args, "requestId", "vmId", "vmName"));
            case WORKSPACE_DELETED -> new Composed(event.id(),
                    "워크스페이스 삭제 안내 — " + str(args, "workspaceName"),
                    """
                    소유자가 워크스페이스 '%s'을(를) 삭제하여 워크스페이스와 구성원 정보가
                    정리되었습니다. 워크스페이스에 연결된 VM이 없는 상태에서만 삭제할 수
                    있으므로 자원에는 영향이 없습니다. 진행 중이던 VM 신청이
                    있었다면 함께 취소되었습니다.""".formatted(str(args, "workspaceName")),
                    null, event.defaultImportance(), payload(args, "workspaceId", "workspaceName"));
        };
    }

    private Composed requestSubmitted(NotificationEvent event, Map<String, Object> args) {
        boolean admin = Boolean.TRUE.equals(args.get("admin"));
        String label = resourceLabel(args);
        String title = admin ? "새 " + label + " 신청 접수" : label + " 신청 접수";
        String body = admin
                ? """
                  워크스페이스 '%s'에서 새 %s 신청이 접수되었습니다. 검토해 주세요.

                  - 신청 목적: %s""".formatted(str(args, "workspaceName"), label, str(args, "purpose"))
                : """
                  워크스페이스 '%s'의 %s 신청이 접수되었습니다. 관리자 검토 후 결과를 알려드립니다.

                  - 신청 목적: %s""".formatted(str(args, "workspaceName"), label, str(args, "purpose"));
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
                ? "VM '%s'의 사용 종료일(%s)이 오늘입니다.".formatted(vmName, args.get("endDate"))
                : "VM '%s'의 사용 종료일(%s)이 %d일 남았습니다.".formatted(
                        vmName, args.get("endDate"), daysLeft);
        return new Composed("vm.expiry.d" + stage, title,
                firstLine + """

                종료일이 지나면 VM이 자동 정지될 수 있습니다. 계속 사용하려면
                관리자에게 기간 연장을 요청해 주세요.""",
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
        Object type = args.get("type");
        if (type == null) {
            return "리소스";
        }
        for (ResourceType candidate : ResourceType.values()) {
            if (candidate.name().equals(String.valueOf(type))) {
                return candidate.label();
            }
        }
        return "리소스";
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
