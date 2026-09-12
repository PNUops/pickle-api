package kr.ac.pusan.pickle.gpu;

import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import org.springframework.stereotype.Service;

@Service
public class GpuNotices {
    private final NotificationService notices;
    public GpuNotices(NotificationService notices) { this.notices = notices; }
    public void holders(GpuAllocation a, String title, String message, String dedup) {
        notices.publish(notices.resourceRecipients(ResourceType.GPU, a.id(), a.workspaceId(), List.of(ResourceRole.OWNER, ResourceRole.EDITOR)),
                NotificationEvent.GPU_UPDATE, Map.of("allocationId", a.publicId(), "title", title, "message", message), dedup);
    }
    public void administrators(String message, String dedup) {
        notices.publish(notices.sysAdminIds(), NotificationEvent.GPU_REVIEW,
                Map.of("message", message), dedup);
    }
}
