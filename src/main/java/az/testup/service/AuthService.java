package az.testup.service;

import az.testup.dto.request.ChangePasswordRequest;
import az.testup.dto.request.LoginRequest;
import az.testup.dto.request.RegisterRequest;
import az.testup.dto.response.AuthResponse;
import az.testup.entity.SubscriptionPlan;
import az.testup.entity.User;
import az.testup.entity.UserSubscription;
import az.testup.enums.AuditAction;
import az.testup.enums.Role;
import az.testup.exception.BadRequestException;
import az.testup.exception.UnauthorizedException;
import az.testup.repository.SubscriptionPlanRepository;
import az.testup.repository.UserRepository;
import az.testup.repository.UserSubscriptionRepository;
import az.testup.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String RESET_OTP_PREFIX = "pwd_reset:";
    private static final long OTP_TTL_MINUTES = 15;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final AuditLogService auditLogService;
    private final RedisTemplate<String, String> redisTemplate;
    private final EmailService emailService;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Bu e-poçt artıq istifadə olunur");
        }

        Role role;
        try {
            role = Role.valueOf(request.getRole().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Yanlış rol: " + request.getRole());
        }

        User user = User.builder()
                .fullName(request.getFullName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .phoneNumber(request.getPhoneNumber())
                .role(role)
                .build();

        user = userRepository.save(user);

        // Auto-assign 3-month Basic plan for new teachers
        boolean giftPlanAssigned = false;
        if (role == Role.TEACHER) {
            Optional<SubscriptionPlan> basicPlan = subscriptionPlanRepository.findByName("Basic");
            if (basicPlan.isPresent()) {
                LocalDateTime now = LocalDateTime.now();
                UserSubscription gift = UserSubscription.builder()
                        .user(user)
                        .plan(basicPlan.get())
                        .startDate(now)
                        .endDate(now.plusMonths(3))
                        .isActive(true)
                        .paymentProvider("GIFT")
                        .transactionId("WELCOME_GIFT")
                        .build();
                userSubscriptionRepository.save(gift);
                giftPlanAssigned = true;
            }
        }

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().name(), user.getFullName());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        auditLogService.log(AuditAction.USER_REGISTERED, user.getEmail(), user.getFullName(), "AUTH", user.getEmail(), null);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .role(user.getRole().name())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .giftPlanAssigned(giftPlanAssigned)
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    auditLogService.log(AuditAction.USER_LOGIN_FAILED, request.getEmail(), request.getEmail(), "AUTH", request.getEmail(), "Yanlış şifrə");
                    return new UnauthorizedException("E-poçt və ya şifrə yanlışdır");
                });

        if (user.getPassword() == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            auditLogService.log(AuditAction.USER_LOGIN_FAILED, request.getEmail(), request.getEmail(), "AUTH", request.getEmail(), "Yanlış şifrə");
            throw new UnauthorizedException("E-poçt və ya şifrə yanlışdır");
        }

        if (!user.isEnabled()) {
            throw new UnauthorizedException("Hesabınız deaktiv edilmişdir. Administratorla əlaqə saxlayın.");
        }

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().name(), user.getFullName());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        auditLogService.log(AuditAction.USER_LOGIN, user.getEmail(), user.getFullName(), "AUTH", user.getEmail(), null);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .role(user.getRole().name())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .build();
    }

    public void changePassword(User user, ChangePasswordRequest request) {
        if (user.getPassword() == null) {
            throw new BadRequestException("Google hesabı ilə qeydiyyatdan keçmisiniz. Şifrə dəyişikliyi mümkün deyil.");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new BadRequestException("Cari şifrə yanlışdır");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    /**
     * Generates a 6-digit OTP, stores it in Redis for 15 minutes, and sends it to the user's email.
     * Always returns silently even if the email is not found (prevents account enumeration).
     */
    public void sendPasswordResetOtp(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) return;
        User user = userOpt.get();
        if (user.getPassword() == null) return; // Google-only accounts cannot reset password

        String otp = String.format("%06d", new Random().nextInt(1_000_000));
        redisTemplate.opsForValue().set(RESET_OTP_PREFIX + email, otp, OTP_TTL_MINUTES, TimeUnit.MINUTES);

        String body = "Şifrənizi sıfırlamaq üçün aşağıdakı kodu istifadə edin:"
                + "<div style='font-size:36px;font-weight:bold;letter-spacing:10px;text-align:center;"
                + "color:#4f46e5;padding:24px 0;'>" + otp + "</div>"
                + "Bu kod <strong>" + OTP_TTL_MINUTES + " dəqiqə</strong> ərzində etibarlıdır.<br><br>"
                + "Əgər bu sorğunu siz göndərməmisinizsə, bu mesajı nəzərə almayın.";
        emailService.sendGmail(email, user.getFullName(),
                "Şifrə Sıfırlama — testup.az",
                emailService.buildHtml("Şifrə Sıfırlama Kodu", body),
                null);
    }

    /**
     * Verifies the OTP and sets a new password. Deletes the OTP from Redis on success.
     */
    public void resetPassword(String email, String otp, String newPassword) {
        String stored = redisTemplate.opsForValue().get(RESET_OTP_PREFIX + email);
        if (stored == null) {
            throw new BadRequestException("Kodun müddəti bitib və ya etibarsızdır");
        }
        if (!stored.equals(otp)) {
            throw new BadRequestException("Kod yanlışdır");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("İstifadəçi tapılmadı"));
        if (user.getPassword() == null) {
            throw new BadRequestException("Google hesabı şifrə sıfırlamasını dəstəkləmir");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        redisTemplate.delete(RESET_OTP_PREFIX + email);
    }

    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new UnauthorizedException("Refresh token etibarsızdır");
        }

        String userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));

        String newAccessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().name(), user.getFullName());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .role(user.getRole().name())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .build();
    }
}
