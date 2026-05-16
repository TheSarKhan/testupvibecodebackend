package az.testup.service;

import az.testup.dto.request.AdminNotificationRequest;
import az.testup.dto.response.NotificationLogResponse;
import az.testup.entity.NotificationLog;
import az.testup.entity.User;
import az.testup.enums.AuditAction;
import az.testup.enums.NotificationType;
import az.testup.enums.Role;
import az.testup.repository.NotificationLogRepository;
import az.testup.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final NotificationLogRepository notificationLogRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public NotificationLogResponse sendAdminNotification(AdminNotificationRequest req,
                                                         MultipartFile attachment,
                                                         String adminEmail) {
        List<User> targets = switch (req.targetType() == null ? "ALL" : req.targetType().toUpperCase()) {
            case "ROLE" -> {
                Role role = Role.valueOf(req.roleFilter().toUpperCase());
                yield userRepository.findByRole(role);
            }
            case "SELECTED" -> userRepository.findAllById(
                    req.userIds() != null ? req.userIds() : List.of());
            default -> userRepository.findAll();
        };

        List<String> channels = req.channels() != null ? req.channels() : List.of();
        String htmlBody = emailService.buildHtml(req.title(), req.description());

        for (User user : targets) {
            if (channels.contains("SITE")) {
                NotificationType notifType = NotificationType.SYSTEM;
                if (req.type() != null) {
                    try {
                        notifType = NotificationType.valueOf(req.type().toUpperCase());
                    } catch (IllegalArgumentException ignored) {}
                }
                notificationService.send(user, req.title(), req.description(), notifType, req.actionUrl());
            }
            if (channels.contains("GMAIL") && user.getEmail() != null) {
                emailService.sendGmail(user.getEmail(), user.getFullName(),
                        req.title(), htmlBody, attachment);
            }
            if (channels.contains("SENDPULSE") && user.getEmail() != null) {
                emailService.sendSendPulse(user.getEmail(), user.getFullName(),
                        req.title(), htmlBody, attachment);
            }
        }

        NotificationLog logEntry = NotificationLog.builder()
                .title(req.title())
                .description(req.description())
                .channels(String.join(",", channels))
                .targetType(req.targetType())
                .roleFilter(req.roleFilter())
                .recipientCount(targets.size())
                .sentBy(adminEmail)
                .build();

        NotificationLogResponse logResponse = toLogResponse(notificationLogRepository.save(logEntry));
        auditLogService.log(AuditAction.NOTIFICATION_SENT, adminEmail, adminEmail,
                "NOTIFICATION", req.title(), "Alıcı sayı: " + targets.size());
        return logResponse;
    }

    @Transactional(readOnly = true)
    public Page<NotificationLogResponse> getHistory(Pageable pageable) {
        return notificationLogRepository.findAllByOrderBySentAtDesc(pageable)
                .map(this::toLogResponse);
    }

    private NotificationLogResponse toLogResponse(NotificationLog entry) {
        return new NotificationLogResponse(
                entry.getId(), entry.getTitle(), entry.getDescription(),
                entry.getChannels(), entry.getTargetType(), entry.getRoleFilter(),
                entry.getRecipientCount(), entry.getSentBy(), entry.getSentAt()
        );
    }
}
