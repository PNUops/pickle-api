package kr.ac.pusan.pickle.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
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

    public Composed compose(NotificationEvent event, Map<String, Object> args) {
        return switch (event) {
            case REQUEST_SUBMITTED -> requestSubmitted(event, args);
            case REQUEST_APPROVED -> new Composed(event.id(), "VM 신청 승인",
                    """
                    VM 신청이 승인되었습니다. VM '%s' 생성이 시작됩니다.
                    생성이 완료되면 다시 알려드립니다.%s""".formatted(str(args, "hostname"),
                            args.get("comment") != null
                                    ? "\n\n- 검토 의견: " + str(args, "comment") : ""),
                    "/console/requests/" + args.get("requestId"), event.defaultImportance(),
                    payload(args, "requestId", "hostname"));
            case REQUEST_REJECTED -> new Composed(event.id(), "VM 신청 반려",
                    """
                    VM 신청이 반려되었습니다.

                    - 반려 사유: %s""".formatted(str(args, "comment")),
                    "/console/requests/" + args.get("requestId"), event.defaultImportance(),
                    payload(args, "requestId"));
            case VM_CREATE_DONE -> new Composed(event.id(),
                    "VM 생성 완료 — " + str(args, "hostname"),
                    """
                    신청하신 VM이 생성되어 실행 중입니다.

                    - 호스트명: %s
                    - 내부 IP: %s
                    - SSH 계정: %s
                    - 초기 비밀번호: 콘솔의 VM 상세 화면에서 확인할 수 있습니다.

                    [중요] 플랫폼은 VM 데이터를 백업하지 않습니다(사용자 책임).
                    중요한 데이터는 반드시 직접 백업해 주세요.
                    """.formatted(str(args, "hostname"),
                            args.get("ip") != null ? str(args, "ip") : "(확인 중)",
                            str(args, "sshUsername")),
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
            // User-facing wording stays on the "who deleted" axis (glossary):
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
        };
    }

    private Composed requestSubmitted(NotificationEvent event, Map<String, Object> args) {
        boolean admin = Boolean.TRUE.equals(args.get("admin"));
        String title = admin ? "새 VM 신청 접수" : "VM 신청 접수";
        String body = admin
                ? """
                  그룹 '%s'에서 새 VM 신청이 접수되었습니다. 검토해 주세요.

                  - 신청 목적: %s""".formatted(str(args, "groupName"), str(args, "purpose"))
                : """
                  그룹 '%s'의 VM 신청이 접수되었습니다. 관리자 검토 후 결과를 알려드립니다.

                  - 신청 목적: %s""".formatted(str(args, "groupName"), str(args, "purpose"));
        String link = (admin ? "/admin/requests/" : "/console/requests/") + args.get("requestId");
        return new Composed(event.id(), title, body, link, event.defaultImportance(),
                payload(args, "requestId", "groupName"));
    }

    /** Per-stage id (vm.expiry.d7 …); the last day (D-1) escalates to HIGH. */
    private Composed expiryNotice(Map<String, Object> args) {
        int days = ((Number) args.get("days")).intValue();
        return new Composed("vm.expiry.d" + days,
                "VM 사용 종료 D-" + days + " — " + str(args, "vmName"),
                """
                VM '%s'의 사용 종료일(%s)이 %d일 남았습니다.
                종료일이 지나면 VM이 자동 정지될 수 있습니다. 계속 사용하려면
                관리자에게 기간 연장을 요청해 주세요.""".formatted(
                        str(args, "vmName"), args.get("endDate"), days),
                "/console/vms/" + args.get("vmId"),
                days <= 1 ? NotificationImportance.HIGH : NotificationImportance.NORMAL,
                payload(args, "vmId", "vmName", "endDate"));
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
        return Instant.parse(String.valueOf(value));
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
