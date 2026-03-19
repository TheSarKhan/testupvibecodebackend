package az.testup.controller;

import az.testup.entity.ContactMessage;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.ContactMessageRepository;
import az.testup.service.EmailService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ContactController {

    private final ContactMessageRepository contactMessageRepository;
    private final EmailService emailService;

    // ── Public: submit contact form ──

    public record ContactRequest(
            @NotBlank(message = "Ad tələb olunur") @Size(max = 100) String name,
            @NotBlank(message = "E-poçt tələb olunur") @Email @Size(max = 150) String email,
            @Size(max = 50) String subject,
            @NotBlank(message = "Mesaj tələb olunur") @Size(max = 3000) String message
    ) {}

    @PostMapping("/api/contact")
    public ResponseEntity<Map<String, String>> submit(@Valid @RequestBody ContactRequest req) {
        contactMessageRepository.save(ContactMessage.builder()
                .name(req.name().trim())
                .email(req.email().trim().toLowerCase())
                .subject(req.subject())
                .message(req.message().trim())
                .build());
        return ResponseEntity.ok(Map.of("message", "Mesajınız göndərildi"));
    }

    // ── Admin: list, read, delete ──

    @GetMapping("/api/admin/contact-messages")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<ContactMessage>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) Boolean read,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String s = (search != null && !search.isBlank()) ? search.trim() : null;
        String sub = (subject != null && !subject.isBlank()) ? subject.trim() : null;
        return ResponseEntity.ok(contactMessageRepository.search(sub, read, s, PageRequest.of(page, size)));
    }

    @GetMapping("/api/admin/contact-messages/unread-count")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        return ResponseEntity.ok(Map.of("count", contactMessageRepository.countByIsReadFalse()));
    }

    @PatchMapping("/api/admin/contact-messages/{id}/read")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        contactMessageRepository.findById(id).ifPresent(m -> {
            m.setRead(true);
            contactMessageRepository.save(m);
        });
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/api/admin/contact-messages/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        contactMessageRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Admin: reply via email ──

    public record ReplyRequest(
            @NotBlank String subject,
            @NotBlank @Size(max = 5000) String body,
            String channel   // "GMAIL" | "SENDPULSE" — default GMAIL
    ) {}

    @PostMapping("/api/admin/contact-messages/{id}/reply")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> reply(
            @PathVariable Long id,
            @Valid @RequestBody ReplyRequest req) {

        ContactMessage msg = contactMessageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mesaj tapılmadı"));

        String html = emailService.buildHtml(req.subject(), req.body());

        if ("SENDPULSE".equalsIgnoreCase(req.channel())) {
            emailService.sendSendPulse(msg.getEmail(), msg.getName(), req.subject(), html, null);
        } else {
            emailService.sendGmail(msg.getEmail(), msg.getName(), req.subject(), html, null);
        }

        // Mark as read after replying
        if (!msg.isRead()) {
            msg.setRead(true);
            contactMessageRepository.save(msg);
        }

        return ResponseEntity.ok(Map.of("message", "Cavab göndərildi"));
    }
}
