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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final AuditLogService auditLogService;

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
