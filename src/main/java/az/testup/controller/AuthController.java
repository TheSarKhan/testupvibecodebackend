package az.testup.controller;

import az.testup.dto.request.ChangePasswordRequest;
import az.testup.dto.request.LoginRequest;
import az.testup.dto.request.RegisterRequest;
import az.testup.dto.response.AuthResponse;
import az.testup.entity.User;
import az.testup.exception.UnauthorizedException;
import az.testup.repository.UserRepository;
import az.testup.service.AuthService;
import az.testup.service.GoogleAuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final GoogleAuthService googleAuthService;
    private final UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        AuthResponse response = authService.refreshToken(refreshToken);
        return ResponseEntity.ok(response);
    }

    // ── Google OAuth2 ──

    public record GoogleAuthRequest(@NotBlank String googleToken) {}

    public record GoogleCompleteRequest(
            @NotBlank String googleToken,
            @NotBlank String role,
            boolean termsAccepted
    ) {}

    @PostMapping("/google")
    public ResponseEntity<Map<String, Object>> googleAuth(@RequestBody GoogleAuthRequest req) {
        Map<String, Object> result = googleAuthService.handleGoogleAuth(req.googleToken());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/google/complete")
    public ResponseEntity<AuthResponse> googleComplete(@RequestBody GoogleCompleteRequest req) {
        AuthResponse response = googleAuthService.completeGoogleRegistration(
                req.googleToken(), req.role(), req.termsAccepted());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) throw new UnauthorizedException("İstifadəçi tapılmadı");
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));
        authService.changePassword(user, request);
        return ResponseEntity.ok().build();
    }
}
