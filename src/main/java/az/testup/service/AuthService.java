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
    private static final String VERIFY_OTP_PREFIX = "email_verify:";
    private static final String VERIFY_THROTTLE_PREFIX = "email_verify_cooldown:";
    private static final String EMAIL_CHANGE_PENDING_PREFIX = "email_change_pending:";
    private static final String EMAIL_CHANGE_OTP_PREFIX = "email_change_otp:";
    private static final String EMAIL_CHANGE_THROTTLE_PREFIX = "email_change_cooldown:";
    private static final long OTP_TTL_MINUTES = 15;
    private static final long RESEND_COOLDOWN_SECONDS = 60;

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
                // Public registration is unverified until the emailed OTP is
                // confirmed; login stays blocked until then.
                .emailVerified(false)
                .build();

        user = userRepository.save(user);

        // Auto-assign 2-month Standart plan for new teachers. The plan name in
        // production has been renamed from "Basic" to "Standart" — try the
        // current name first and fall back to the legacy one so this still
        // works on databases that haven't been re-seeded yet.
        boolean giftPlanAssigned = false;
        if (role == Role.TEACHER) {
            Optional<SubscriptionPlan> giftPlan = subscriptionPlanRepository.findByName("Standart")
                    .or(() -> subscriptionPlanRepository.findByName("Basic"));
            if (giftPlan.isPresent()) {
                LocalDateTime now = LocalDateTime.now();
                UserSubscription gift = UserSubscription.builder()
                        .user(user)
                        .plan(giftPlan.get())
                        .startDate(now)
                        .endDate(now.plusMonths(2))
                        .isActive(true)
                        .paymentProvider("GIFT")
                        // transaction_id has a unique constraint — postfix with user id so
                        // every gifted subscription gets its own value.
                        .transactionId("WELCOME_GIFT_" + user.getId())
                        .build();
                userSubscriptionRepository.save(gift);
                giftPlanAssigned = true;
                auditLogService.log(AuditAction.SUBSCRIPTION_GIFTED, user.getEmail(), user.getFullName(),
                        "SUBSCRIPTION", giftPlan.get().getName(),
                        "Yeni müəllim qeydiyyatı — 2 aylıq pulsuz " + giftPlan.get().getName() + " plan");
            }
        }

        auditLogService.log(AuditAction.USER_REGISTERED, user.getEmail(), user.getFullName(), "AUTH", user.getEmail(), null);

        // No tokens yet — the account is created but unverified. Send the OTP and
        // tell the client to route to the verification screen. Tokens are issued
        // by verifyEmail() once the code is confirmed.
        sendVerificationOtp(user);

        return AuthResponse.builder()
                .role(user.getRole().name())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .giftPlanAssigned(giftPlanAssigned)
                .emailVerificationRequired(true)
                .build();
    }

    /**
     * Generates a 6-digit verification OTP, stores it in Redis for 15 minutes,
     * and emails it to the user. Respects a short resend cooldown so a button
     * masher (or a register→login bounce) can't fan out a burst of mails.
     */
    private void sendVerificationOtp(User user) {
        String email = user.getEmail();
        String otp = String.format("%06d", new Random().nextInt(1_000_000));
        redisTemplate.opsForValue().set(VERIFY_OTP_PREFIX + email, otp, OTP_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(VERIFY_THROTTLE_PREFIX + email, "1", RESEND_COOLDOWN_SECONDS, TimeUnit.SECONDS);

        String body = "testup.az hesabınızı təsdiqləmək üçün aşağıdakı kodu daxil edin:"
                + "<div style='font-size:36px;font-weight:bold;letter-spacing:10px;text-align:center;"
                + "color:#4f46e5;padding:24px 0;'>" + otp + "</div>"
                + "Bu kod <strong>" + OTP_TTL_MINUTES + " dəqiqə</strong> ərzində etibarlıdır.<br><br>"
                + "Əgər bu qeydiyyatı siz etməmisinizsə, bu mesajı nəzərə almayın.";
        emailService.sendGmail(email, user.getFullName(),
                "E-poçt Təsdiqi — testup.az",
                emailService.buildHtmlRaw("E-poçt Təsdiq Kodu", body),
                null);

        auditLogService.log(AuditAction.EMAIL_VERIFICATION_SENT, user.getEmail(), user.getFullName(),
                "AUTH", user.getEmail(), "Təsdiq kodu göndərildi");
    }

    /**
     * Confirms the email-verification OTP. On success marks the account verified,
     * clears the code, and issues tokens (auto-login) so the user lands signed in.
     */
    public AuthResponse verifyEmail(String email, String otp) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("İstifadəçi tapılmadı"));
        if (user.isEmailVerified()) {
            // Already verified (e.g. double-submit) — just log them in.
            return issueTokens(user);
        }
        String stored = redisTemplate.opsForValue().get(VERIFY_OTP_PREFIX + email);
        if (stored == null) {
            throw new BadRequestException("Kodun müddəti bitib və ya etibarsızdır");
        }
        if (!stored.equals(otp)) {
            throw new BadRequestException("Kod yanlışdır");
        }
        user.setEmailVerified(true);
        userRepository.save(user);
        redisTemplate.delete(VERIFY_OTP_PREFIX + email);
        redisTemplate.delete(VERIFY_THROTTLE_PREFIX + email);
        auditLogService.log(AuditAction.EMAIL_VERIFIED, user.getEmail(), user.getFullName(),
                "AUTH", user.getEmail(), "E-poçt təsdiqləndi");
        return issueTokens(user);
    }

    /**
     * Re-sends the verification OTP for an unverified account. Silent for unknown
     * or already-verified emails (no account enumeration). Throttled so resends
     * can't be spammed.
     */
    public void resendVerification(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty() || userOpt.get().isEmailVerified()) {
            return;
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey(VERIFY_THROTTLE_PREFIX + email))) {
            throw new BadRequestException("Yeni kod istəmədən əvvəl bir az gözləyin");
        }
        sendVerificationOtp(userOpt.get());
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().name(), user.getFullName());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .role(user.getRole().name())
                .fullName(user.getFullName())
                .email(user.getEmail())
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

        // Email must be confirmed before login. Send a fresh code (cooldown-aware:
        // sendVerificationOtp resets the throttle, so a verify-screen "resend" will
        // be gated, but logging in right after registering still gets a code) and
        // tell the client to route to the verification screen instead of issuing
        // tokens. Credentials are already proven valid at this point.
        if (!user.isEmailVerified()) {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(VERIFY_THROTTLE_PREFIX + user.getEmail()))) {
                sendVerificationOtp(user);
            }
            return AuthResponse.builder()
                    .fullName(user.getFullName())
                    .email(user.getEmail())
                    .emailVerificationRequired(true)
                    .build();
        }

        auditLogService.log(AuditAction.USER_LOGIN, user.getEmail(), user.getFullName(), "AUTH", user.getEmail(), null);

        return issueTokens(user);
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
        auditLogService.log(AuditAction.PASSWORD_CHANGED, user.getEmail(), user.getFullName(), "AUTH", user.getEmail(), null);
    }

    /**
     * Generates a 6-digit OTP, stores it in Redis for 15 minutes, and sends it to the user's email.
     * Always returns silently even if the email is not found (prevents account enumeration).
     */
    public void sendPasswordResetOtp(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            // Log attempts on non-existent emails too (account enumeration / abuse detection)
            auditLogService.log(AuditAction.PASSWORD_RESET_REQUESTED, email, email,
                    "AUTH", email, "İstifadəçi tapılmadı");
            return;
        }
        User user = userOpt.get();
        if (user.getPassword() == null) {
            auditLogService.log(AuditAction.PASSWORD_RESET_REQUESTED, user.getEmail(), user.getFullName(),
                    "AUTH", user.getEmail(), "Google hesabı — sıfırlama dəstəklənmir");
            return;
        }

        String otp = String.format("%06d", new Random().nextInt(1_000_000));
        redisTemplate.opsForValue().set(RESET_OTP_PREFIX + email, otp, OTP_TTL_MINUTES, TimeUnit.MINUTES);

        String body = "Şifrənizi sıfırlamaq üçün aşağıdakı kodu istifadə edin:"
                + "<div style='font-size:36px;font-weight:bold;letter-spacing:10px;text-align:center;"
                + "color:#4f46e5;padding:24px 0;'>" + otp + "</div>"
                + "Bu kod <strong>" + OTP_TTL_MINUTES + " dəqiqə</strong> ərzində etibarlıdır.<br><br>"
                + "Əgər bu sorğunu siz göndərməmisinizsə, bu mesajı nəzərə almayın.";
        emailService.sendGmail(email, user.getFullName(),
                "Şifrə Sıfırlama — testup.az",
                emailService.buildHtmlRaw("Şifrə Sıfırlama Kodu", body),
                null);

        auditLogService.log(AuditAction.PASSWORD_RESET_REQUESTED, user.getEmail(), user.getFullName(),
                "AUTH", user.getEmail(), "OTP göndərildi");
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
        auditLogService.log(AuditAction.PASSWORD_RESET_COMPLETED, user.getEmail(), user.getFullName(),
                "AUTH", user.getEmail(), "OTP ilə yeniləndi");
    }

    /**
     * Step 1 of changing the signed-in user's email: validate the new address,
     * stash it as pending in Redis keyed by user id, and send a confirmation OTP
     * to the NEW address (proving the user controls it). The account's email is
     * NOT touched until confirmEmailChange() succeeds.
     */
    public void requestEmailChange(User user, String newEmailRaw) {
        if (user.getPassword() == null) {
            throw new BadRequestException("Google hesabının e-poçtu dəyişdirilə bilməz");
        }
        if (newEmailRaw == null || newEmailRaw.isBlank()) {
            throw new BadRequestException("Yeni e-poçt boş ola bilməz");
        }
        String newEmail = newEmailRaw.trim().toLowerCase();
        if (newEmail.equals(user.getEmail().toLowerCase())) {
            throw new BadRequestException("Yeni e-poçt cari e-poçtla eynidir");
        }
        if (userRepository.existsByEmail(newEmail)) {
            throw new BadRequestException("Bu e-poçt artıq istifadə olunur");
        }
        String throttleKey = EMAIL_CHANGE_THROTTLE_PREFIX + user.getId();
        if (Boolean.TRUE.equals(redisTemplate.hasKey(throttleKey))) {
            throw new BadRequestException("Yeni kod istəmədən əvvəl bir az gözləyin");
        }

        String otp = String.format("%06d", new Random().nextInt(1_000_000));
        redisTemplate.opsForValue().set(EMAIL_CHANGE_PENDING_PREFIX + user.getId(), newEmail, OTP_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(EMAIL_CHANGE_OTP_PREFIX + user.getId(), otp, OTP_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(throttleKey, "1", RESEND_COOLDOWN_SECONDS, TimeUnit.SECONDS);

        String body = "testup.az hesabınızın e-poçt ünvanını bu ünvana dəyişmək üçün aşağıdakı kodu daxil edin:"
                + "<div style='font-size:36px;font-weight:bold;letter-spacing:10px;text-align:center;"
                + "color:#4f46e5;padding:24px 0;'>" + otp + "</div>"
                + "Bu kod <strong>" + OTP_TTL_MINUTES + " dəqiqə</strong> ərzində etibarlıdır.<br><br>"
                + "Əgər bu dəyişikliyi siz istəməmisinizsə, bu mesajı nəzərə almayın.";
        emailService.sendGmail(newEmail, user.getFullName(),
                "E-poçt Dəyişikliyi Təsdiqi — testup.az",
                emailService.buildHtmlRaw("E-poçt Dəyişikliyi Kodu", body),
                null);

        auditLogService.log(AuditAction.EMAIL_CHANGE_REQUESTED, user.getEmail(), user.getFullName(),
                "AUTH", user.getEmail(), "Yeni ünvan: " + newEmail);
    }

    /**
     * Step 2: confirm the OTP and apply the pending email. Issues fresh tokens
     * because the email is baked into the JWT claims, so the old tokens carry a
     * stale address.
     */
    public AuthResponse confirmEmailChange(User user, String otp) {
        String pendingKey = EMAIL_CHANGE_PENDING_PREFIX + user.getId();
        String otpKey = EMAIL_CHANGE_OTP_PREFIX + user.getId();
        String pendingEmail = redisTemplate.opsForValue().get(pendingKey);
        String stored = redisTemplate.opsForValue().get(otpKey);
        if (pendingEmail == null || stored == null) {
            throw new BadRequestException("Kodun müddəti bitib və ya etibarsızdır");
        }
        if (!stored.equals(otp)) {
            throw new BadRequestException("Kod yanlışdır");
        }
        // Re-check uniqueness in case someone else claimed the address in the window.
        if (userRepository.existsByEmail(pendingEmail)) {
            throw new BadRequestException("Bu e-poçt artıq istifadə olunur");
        }
        String oldEmail = user.getEmail();
        user.setEmail(pendingEmail);
        user.setEmailVerified(true);
        userRepository.save(user);
        redisTemplate.delete(pendingKey);
        redisTemplate.delete(otpKey);
        redisTemplate.delete(EMAIL_CHANGE_THROTTLE_PREFIX + user.getId());
        auditLogService.log(AuditAction.EMAIL_CHANGED, user.getEmail(), user.getFullName(),
                "AUTH", user.getEmail(), "Köhnə ünvan: " + oldEmail);
        return issueTokens(user);
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
