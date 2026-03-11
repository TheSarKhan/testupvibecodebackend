package az.testup.controller;

import az.testup.dto.response.DepotExamResponse;
import az.testup.entity.User;
import az.testup.exception.UnauthorizedException;
import az.testup.repository.UserRepository;
import az.testup.service.DepotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/depot")
@RequiredArgsConstructor
public class DepotController {

    private final DepotService depotService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<DepotExamResponse>> getDepot(
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getUser(userDetails);
        return ResponseEntity.ok(depotService.getDepot(student));
    }

    @PostMapping("/{shareLink}")
    public ResponseEntity<Map<String, Object>> saveExam(
            @PathVariable String shareLink,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getUser(userDetails);
        depotService.saveExam(shareLink, student);
        return ResponseEntity.ok(Map.of("saved", true));
    }

    @DeleteMapping("/{shareLink}")
    public ResponseEntity<Map<String, Object>> removeExam(
            @PathVariable String shareLink,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getUser(userDetails);
        depotService.removeExam(shareLink, student);
        return ResponseEntity.ok(Map.of("saved", false));
    }

    @GetMapping("/{shareLink}/status")
    public ResponseEntity<Map<String, Object>> isSaved(
            @PathVariable String shareLink,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getUser(userDetails);
        boolean saved = depotService.isSaved(shareLink, student);
        return ResponseEntity.ok(Map.of("saved", saved));
    }

    private User getUser(UserDetails userDetails) {
        if (userDetails == null) throw new UnauthorizedException("Giriş tələb olunur");
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));
    }
}
