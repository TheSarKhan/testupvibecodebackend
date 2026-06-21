package az.testup.entity;

import az.testup.enums.Role;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column
    private String password;

    @Column(unique = true)
    private String googleSub;

    @Column(length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(columnDefinition = "TEXT")
    private String profilePicture;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    // True once the user has confirmed the OTP sent to their email. Defaults to
    // true so every internally-created account (seeded admins, Google-OAuth users
    // whose address the provider already verified) is usable immediately; only
    // the public email/password registration flow sets this false and flips it
    // back to true after the verification code is confirmed.
    @Builder.Default
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = true;

    // Soft-delete marker. Set when an account that authored content (exams,
    // templates, banks) is "deleted": the row is kept (so student results on
    // the teacher's exams survive) but anonymized, disabled, and hidden from
    // the admin list. Content-free accounts are hard-deleted instead.
    @Builder.Default
    @Column(nullable = false)
    private boolean deleted = false;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
