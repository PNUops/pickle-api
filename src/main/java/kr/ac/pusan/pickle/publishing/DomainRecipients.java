package kr.ac.pusan.pickle.publishing;

import java.util.List;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.notification.NotificationService;

/**
 * Who hears about a domain that stands on its own.
 *
 * <p>Its own access list plus the owners of the workspace that holds it, the
 * same rule every other resource answers with. Held in one place because two
 * jobs ask it and a domain notice sent to the wrong list is not a thing anyone
 * would notice: the notification simply arrives somewhere else.</p>
 */
final class DomainRecipients {

    private DomainRecipients() {
    }

    static List<Long> of(NotificationService notifications, Domain domain) {
        return notifications.resourceRecipients(ResourceType.DOMAIN, domain.getId(),
                domain.getWorkspaceId(), List.of(ResourceRole.OWNER, ResourceRole.EDITOR));
    }
}
