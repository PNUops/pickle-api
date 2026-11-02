package kr.ac.pusan.pickle.notification;

import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.notification.dto.NotificationView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import kr.ac.pusan.pickle.llm.alert.LlmUsageAlertAccess;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read side of the inbox (contract {@code listNotifications}/{@code getUnreadNotificationCount}). */
@Service
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;
    private final JdbcTemplate jdbc;

    public NotificationQueryService(NotificationRepository notificationRepository, JdbcTemplate jdbc) {
        this.notificationRepository = notificationRepository;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<NotificationView> list(long userId, boolean unreadOnly, int page, int size) {
        String where = "n.user_id=? and " + LlmUsageAlertAccess.notificationRead("n")
                + (unreadOnly ? " and n.read_at is null" : "");
        long total = jdbc.queryForObject("select count(*) from notifications n where " + where, Long.class, userId);
        var ids = jdbc.query("select n.id from notifications n where " + where
                + " order by n.created_at desc,n.id desc limit ? offset ?", (rs, n) -> rs.getLong(1),
                userId, size, (long) page * size);
        Map<Long, Notification> loaded = notificationRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Notification::getId, Function.identity()));
        return new PageResponse<>(ids.stream().map(loaded::get).map(NotificationView::from).toList(),
                page, size, total, (int) ((total + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        return jdbc.queryForObject("select count(*) from notifications n where n.user_id=? "
                + "and n.read_at is null and " + LlmUsageAlertAccess.notificationRead("n"), Long.class, userId);
    }
}
