package az.testup.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${spring.mail.username}")
    private String gmailFrom;

    @Value("${sendpulse.client-id:}")
    private String sendpulseClientId;

    @Value("${sendpulse.client-secret:}")
    private String sendpulseClientSecret;

    @Value("${sendpulse.from-email:}")
    private String sendpulseFromEmail;

    @Value("${sendpulse.from-name:testup.az}")
    private String sendpulseFromName;

    // SendPulse token cache
    private String cachedToken;
    private LocalDateTime tokenExpiry;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // ─── Gmail ───────────────────────────────────────────────────────────────

    public void sendGmail(String to, String toName, String subject, String htmlBody, MultipartFile attachment) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(gmailFrom, "testup.az");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            if (attachment != null && !attachment.isEmpty()) {
                String filename = Objects.requireNonNullElse(attachment.getOriginalFilename(), "attachment");
                helper.addAttachment(filename, new ByteArrayResource(attachment.getBytes()));
            }
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Gmail göndərmə xətası [{}]: {}", to, e.getMessage());
        }
    }

    // ─── SendPulse ────────────────────────────────────────────────────────────

    public void sendSendPulse(String to, String toName, String subject, String htmlBody, MultipartFile attachment) {
        try {
            String token = getSendPulseToken();
            if (token == null) {
                log.error("SendPulse token alına bilmədi, {} ünvanına göndərilmir", to);
                return;
            }

            Map<String, Object> fromMap = Map.of("name", sendpulseFromName, "email", sendpulseFromEmail);
            Map<String, Object> toMap = Map.of(
                    "name", toName != null && !toName.isBlank() ? toName : to,
                    "email", to
            );

            Map<String, Object> emailPayload = new HashMap<>();
            emailPayload.put("subject", subject);
            emailPayload.put("html", htmlBody);
            emailPayload.put("from", fromMap);
            emailPayload.put("to", List.of(toMap));

            if (attachment != null && !attachment.isEmpty()) {
                String encoded = Base64.getEncoder().encodeToString(attachment.getBytes());
                String filename = Objects.requireNonNullElse(attachment.getOriginalFilename(), "attachment");
                emailPayload.put("attachments_binary", Map.of(filename, encoded));
            }

            Map<String, Object> body = Map.of("email", emailPayload);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            restTemplate.exchange(
                    "https://api.sendpulse.com/smtp/emails",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
        } catch (Exception e) {
            log.error("SendPulse göndərmə xətası [{}]: {}", to, e.getMessage());
        }
    }

    // ─── HTML builder ─────────────────────────────────────────────────────────

    public String buildHtml(String title, String description) {
        String safeDesc = description != null
                ? description.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
                : "";
        return """
                <!DOCTYPE html>
                <html lang="az">
                <body style="margin:0;padding:0;background:#f3f4f6;font-family:Arial,sans-serif;">
                <div style="max-width:600px;margin:40px auto;background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.08);">
                  <div style="background:linear-gradient(135deg,#4f46e5,#7c3aed);padding:36px 40px;text-align:center;">
                    <h1 style="margin:0;color:#fff;font-size:22px;letter-spacing:.5px;">TestUP</h1>
                  </div>
                  <div style="padding:36px 40px;">
                    <h2 style="margin:0 0 16px;color:#1f2937;font-size:20px;">%s</h2>
                    <div style="color:#4b5563;line-height:1.7;font-size:15px;">%s</div>
                  </div>
                  <div style="padding:20px 40px;border-top:1px solid #f3f4f6;text-align:center;">
                    <p style="margin:0;color:#9ca3af;font-size:12px;">Bu mesaj TestUP platforması tərəfindən göndərilmişdir.</p>
                  </div>
                </div>
                </body></html>
                """.formatted(title, safeDesc);
    }

    // ─── SendPulse token (cached) ─────────────────────────────────────────────

    private synchronized String getSendPulseToken() {
        if (cachedToken != null && tokenExpiry != null && LocalDateTime.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }
        if (sendpulseClientId == null || sendpulseClientId.isBlank()) {
            log.warn("SENDPULSE_CLIENT_ID konfiqurasiya edilməyib");
            return null;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", sendpulseClientId);
            form.add("client_secret", sendpulseClientSecret);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://api.sendpulse.com/oauth/access_token",
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers),
                    Map.class
            );

            Map<?, ?> body = response.getBody();
            if (body != null && body.containsKey("access_token")) {
                cachedToken = (String) body.get("access_token");
                tokenExpiry = LocalDateTime.now().plusMinutes(55);
                return cachedToken;
            }
        } catch (Exception e) {
            log.error("SendPulse token xətası: {}", e.getMessage());
        }
        return null;
    }
}
