package az.testup.entity;

import az.testup.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private NotificationType type = NotificationType.SYSTEM;

    @Column
    private String actionUrl;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Stored as a UTC Instant (mirrors Submission/ExamAccessCode) so the field
    // serialises as a zone-marked ISO string (…Z) and the frontend can parse it
    // unambiguously. LocalDateTime.now() previously emitted naked Baku wall-clock
    // timestamps, which the client mis-read as UTC and pinned to "İndicə".
    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
