package kr.ac.pusan.pickle.notification;

import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.notification.dto.NotificationView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the inbox (contract {@code listNotifications}/{@code getUnreadNotificationCount}). */
@Service
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;

    public NotificationQueryService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> list(long userId, boolean unreadOnly, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Notification> result = unreadOnly
                ? notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(
                        userId, pageable)
                : notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
        return PageResponse.of(result.getContent().stream().map(NotificationView::from).toList(),
                result);
    }

    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }
}
