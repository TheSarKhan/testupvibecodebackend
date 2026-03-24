package az.testup.service;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final AuditLogService auditLogService;
    private final RestTemplate restTemplate;

    @Value("${google.client-id}")
    private String googleClientId;

    /**
     * Verifies a Google access token and returns user info (sub, email, name, picture).
     */
    public Map<String, Object> verifyGoogleToken(String accessToken) {
        try {
            // 1. Verify token via tokeninfo — checks azp (authorized party = client ID)
            String tokenInfoUrl = "https://www.googleapis.com/oauth2/v3/tokeninfo?access_token=" + accessToken;
            ResponseEntity<Map> tokenInfoResp = restTemplate.getForEntity(tokenInfoUrl, Map.class);
            Map<String, Object> tokenInfo = tokenInfoResp.getBody();
            if (tokenInfo == null || tokenInfo.containsKey("error_description")) {
                throw new UnauthorizedException("Google token doğrulaması uğursuz oldu");
            }
            String azp = (String) tokenInfo.get("azp");
            if (!googleClientId.equals(azp)) {
                throw new UnauthorizedException("Google token etibarsızdır");
            }

            // 2. Fetch user info (name, picture, sub, email)
            String userInfoUrl = "https://www.googleapis.com/oauth2/v1/userinfo?access_token=" + accessToken;
            ResponseEntity<Map> userInfoResp = restTemplate.getForEntity(userInfoUrl, Map.class);
            Map<String, Object> userInfo = userInfoResp.getBody();
            if (userInfo == null) {
                throw new UnauthorizedException("İstifadəçi məlumatları alına bilmədi");
            }

            // Normalize: Google userinfo returns "id" but our code expects "sub"
            Map<String, Object> result = new HashMap<>(userInfo);
            if (userInfo.containsKey("id") && !userInfo.containsKey("sub")) {
                result.put("sub", userInfo.get("id"));
            }
            return result;
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            throw new UnauthorizedException("Google token doğrulaması uğursuz oldu: " + e.getMessage());
        }
    }

    /**
     * POST /api/auth/google
     * Returns {status: "LOGIN", ...AuthResponse} or {status: "NEEDS_REGISTRATION", email, name, picture}
     */
    public Map<String, Object> handleGoogleAuth(String idToken) {
        Map<String, Object> tokenInfo = verifyGoogleToken(idToken);

        String googleSub = (String) tokenInfo.get("sub");
        String email = (String) tokenInfo.get("email");
        String name = (String) tokenInfo.get("name");
        String picture = (String) tokenInfo.get("picture");

        // Try find by googleSub first, then by email
        Optional<User> userOpt = userRepository.findByGoogleSub(googleSub);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByEmail(email);
        }

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (!user.isEnabled()) {
                throw new UnauthorizedException("Hesabınız deaktiv edilmişdir.");
            }
            // Link googleSub if not set yet (user registered normally, now logging in with Google)
            if (user.getGoogleSub() == null) {
                user.setGoogleSub(googleSub);
                if (picture != null && (user.getProfilePicture() == null || user.getProfilePicture().isBlank())) {
                    user.setProfilePicture(picture);
                }
                userRepository.save(user);
            }
            String accessToken = jwtTokenProvider.generateAccessToken(
                    user.getId(), user.getEmail(), user.getRole().name(), user.getFullName());
            String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
            auditLogService.log(AuditAction.USER_LOGIN, user.getEmail(), user.getFullName(), "AUTH", user.getEmail(), "Google OAuth");

            return Map.of(
                    "status", "LOGIN",
                    "accessToken", accessToken,
                    "refreshToken", refreshToken,
                    "role", user.getRole().name(),
                    "fullName", user.getFullName(),
                    "email", user.getEmail()
            );
        }

        // New user — needs to select role
        return Map.of(
                "status", "NEEDS_REGISTRATION",
                "email", email != null ? email : "",
                "name", name != null ? name : "",
                "picture", picture != null ? picture : "",
                "googleSub", googleSub
        );
    }

    /**
     * POST /api/auth/google/complete
     * Completes registration for a new Google user with chosen role.
     */
    public AuthResponse completeGoogleRegistration(String idToken, String role, String phoneNumber, boolean termsAccepted) {
        if (!termsAccepted) {
            throw new BadRequestException("İstifadə şərtlərini qəbul etməlisiniz");
        }

        Map<String, Object> tokenInfo = verifyGoogleToken(idToken);

        String googleSub = (String) tokenInfo.get("sub");
        String email = (String) tokenInfo.get("email");
        String name = (String) tokenInfo.get("name");
        String picture = (String) tokenInfo.get("picture");

        if (userRepository.findByGoogleSub(googleSub).isPresent()) {
            throw new BadRequestException("Bu Google hesabı artıq qeydiyyatdan keçib");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BadRequestException("Bu e-poçt artıq istifadə olunur");
        }

        Role userRole;
        try {
            userRole = Role.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Yanlış rol: " + role);
        }

        User user = User.builder()
                .fullName(name)
                .email(email)
                .password(null)
                .googleSub(googleSub)
                .profilePicture(picture)
                .phoneNumber(phoneNumber)
                .role(userRole)
                .build();

        user = userRepository.save(user);

        boolean giftPlanAssigned = false;
        if (userRole == Role.TEACHER) {
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

        auditLogService.log(AuditAction.USER_REGISTERED, user.getEmail(), user.getFullName(), "AUTH", user.getEmail(), "Google OAuth");

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .role(user.getRole().name())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .giftPlanAssigned(giftPlanAssigned)
                .build();
    }
}
