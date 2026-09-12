package kr.ac.pusan.pickle.gpu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.notification.NotificationComposer;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import org.junit.jupiter.api.Test;

class GpuNotificationTest {
    private final NotificationComposer composer = new NotificationComposer("ssh.example.com");
    @Test
    void allocationMailLinksToTheExistingConsoleDetailRoute() {
        UUID id = UUID.randomUUID();
        var notice = composer.compose(NotificationEvent.GPU_UPDATE, Map.of("allocationId",id,"title","GPU 할당 완료","message","임대가 시작되었습니다."));
        assertThat(notice.linkPath()).isEqualTo("/console/gpus/"+id);
    }
    @Test
    void operatorMailLinksToTheGpuReviewScreen() {
        var notice = composer.compose(NotificationEvent.GPU_REVIEW, Map.of("message","GPU 미연결 상태를 검토해 주세요."));
        assertThat(notice.linkPath()).isEqualTo("/admin/gpus");
    }
}
