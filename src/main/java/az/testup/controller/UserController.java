package az.testup.controller;

import az.testup.entity.User;
import az.testup.exception.UnauthorizedException;
import az.testup.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMe(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) throw new UnauthorizedException("İstifadəçi tapılmadı");
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));
        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "fullName", user.getFullName(),
                "email", user.getEmail(),
                "role", user.getRole().name(),
                "profilePicture", user.getProfilePicture() != null ? user.getProfilePicture() : ""
        ));
    }

    @PostMapping("/me/picture")
    public ResponseEntity<Map<String, String>> uploadPicture(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) throw new UnauthorizedException("İstifadəçi tapılmadı");
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));
        String picture = body.get("picture");
        user.setProfilePicture(picture);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("profilePicture", picture != null ? picture : ""));
    }

    @DeleteMapping("/me/picture")
    public ResponseEntity<Void> deletePicture(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) throw new UnauthorizedException("İstifadəçi tapılmadı");
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));
        user.setProfilePicture(null);
        userRepository.save(user);
        return ResponseEntity.ok().build();
    }
}
