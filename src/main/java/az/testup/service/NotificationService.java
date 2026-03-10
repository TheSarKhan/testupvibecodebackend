package az.testup.service;

import az.testup.entity.Notification;
import az.testup.entity.User;
import az.testup.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public void send(User user, String title, String message) {
        Notification n = Notification.builder()
                .user(user)
                .title(title)
                .message(message)
                .build();
        notificationRepository.save(n);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getForUser(User user) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(n -> Map.<String, Object>of(
                        "id", n.getId(),
                        "title", n.getTitle(),
                        "message", n.getMessage() != null ? n.getMessage() : "",
                        "isRead", n.getIsRead(),
                        "createdAt", n.getCreatedAt().toString()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public long countUnread(User user) {
        return notificationRepository.countByUserIdAndIsReadFalse(user.getId());
    }

    @Transactional
    public void markRead(Long notificationId, User user) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (n.getUser().getId().equals(user.getId())) {
                n.setIsRead(true);
                notificationRepository.save(n);
            }
        });
    }

    @Transactional
    public void markAllRead(User user) {
        List<Notification> unread = notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream().filter(n -> !n.getIsRead()).toList();
        unread.forEach(n -> n.setIsRead(true));
        notificationRepository.saveAll(unread);
    }
}
