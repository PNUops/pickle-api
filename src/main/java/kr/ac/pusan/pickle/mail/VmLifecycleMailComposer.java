package kr.ac.pusan.pickle.mail;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Korean plain-text mails for the VM deletion flows (docs/plan/03, contract
 * v0.3.1 deletion policy). Every deletion mail restates the two policy facts
 * users must not miss: the platform keeps <b>no backups</b> (destroyed data is
 * unrecoverable) and <b>only admins can cancel</b> a pending deletion.
 */
@Component
public class VmLifecycleMailComposer {

    static final String BACKUP_NOTICE =
            "플랫폼은 VM 데이터를 백업하지 않으며 삭제 후 복구할 수 없습니다. "
                    + "필요한 데이터는 파기 전에 직접 백업해 주세요.";

    static final String CANCEL_POLICY_NOTICE =
            "삭제 취소는 관리자만 가능합니다. 복원이 필요하면 관리자에게 문의해 주세요.";

    private static final DateTimeFormatter KST =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private static final String FOOTER = "\n— Pickle 운영팀\n";

    /** Self-delete accepted: VM shut down, destruction after the grace period. */
    public MailMessage selfDeleteAccepted(String to, String vmName, Instant scheduledFor) {
        return new MailMessage(to, "[Pickle] VM 삭제 접수 안내 — " + vmName, """
                안녕하세요, Pickle입니다.

                VM '%s'의 삭제 요청이 접수되었습니다. VM은 곧 종료되며,
                %s (KST) 이후 완전히 파기될 예정입니다.

                - %s
                - %s
                """.formatted(vmName, KST.format(scheduledFor), CANCEL_POLICY_NOTICE, BACKUP_NOTICE)
                + FOOTER);
    }

    /** Admin-scheduled routine delete: reason + destroy date + policy notices. */
    public MailMessage adminDeleteScheduled(String to, String vmName, String reason,
            Instant scheduledFor) {
        return new MailMessage(to, "[Pickle] VM 삭제 예약 안내 — " + vmName, """
                안녕하세요, Pickle입니다.

                관리자가 VM '%s'의 삭제를 예약했습니다.

                - 사유: %s
                - 파기 예정 시각: %s (KST)

                - %s
                - %s
                """.formatted(vmName, reason, KST.format(scheduledFor),
                CANCEL_POLICY_NOTICE, BACKUP_NOTICE) + FOOTER);
    }

    /** A pending deletion was canceled by an admin. */
    public MailMessage deleteCanceled(String to, String vmName) {
        return new MailMessage(to, "[Pickle] VM 삭제 예약 취소 안내 — " + vmName, """
                안녕하세요, Pickle입니다.

                VM '%s'에 예약되어 있던 삭제가 관리자에 의해 취소되었습니다.
                VM과 데이터는 그대로 유지됩니다. (셀프 삭제로 종료되었던 VM은
                STOPPED 상태로 남아 있으며, 콘솔에서 직접 시작할 수 있습니다.)
                """.formatted(vmName) + FOOTER);
    }

    /** SYS_ADMIN emergency delete: immediate stop + destroy, not cancelable. */
    public MailMessage emergencyDeleteAccepted(String to, String vmName) {
        return new MailMessage(to, "[Pickle] VM 긴급 삭제 통지 — " + vmName, """
                안녕하세요, Pickle입니다.

                보안 사고 등 긴급 사유로 관리자가 VM '%s'를 즉시 강제 종료하고
                파기했습니다. 긴급 삭제는 취소할 수 없습니다.

                - %s

                문의 사항은 관리자에게 연락해 주세요.
                """.formatted(vmName, BACKUP_NOTICE) + FOOTER);
    }

    /** Final destruction done (grace elapsed) — org admins are notified. */
    public MailMessage deleteCompleted(String to, String vmName) {
        return new MailMessage(to, "[Pickle] VM 파기 완료 안내 — " + vmName, """
                안녕하세요, Pickle입니다.

                VM '%s'의 유예 기간이 끝나 파기가 완료되었습니다.
                할당되었던 자원(IP 등)은 회수되었으며, 이 작업은 되돌릴 수 없습니다.
                """.formatted(vmName) + FOOTER);
    }
}
