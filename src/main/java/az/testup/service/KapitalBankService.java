package az.testup.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KapitalBankService {

    private static final Logger log = LoggerFactory.getLogger(KapitalBankService.class);

    @Value("${kapitalbank.username}")
    private String kbUsername;

    @Value("${kapitalbank.password}")
    private String kbPassword;

    @Value("${kapitalbank.production:false}")
    private boolean production;

    @Value("${app.base-url}")
    private String appBaseUrl;

    private final RestTemplate restTemplate;

    public record CreateInvoiceResult(String orderId, String password, String paymentUrl) {}

    private String baseUrl() {
        return production
                ? "https://e-commerce.kapitalbank.az/api"
                : "https://txpgtst.kapitalbank.az/api";
    }

    private HttpHeaders buildHeaders() {
        String credentials = kbUsername + ":" + kbPassword;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encoded);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // Kapital Bank's HPP rejects descriptions containing non-Latin letters,
    // em/en dashes, and other Unicode "smart" punctuation with the generic
    // `ServiceError / ApeError` response — the only signal you get back is
    // a 500 with that opaque payload. We transliterate Azerbaijani letters
    // to ASCII, fold smart punctuation to plain ASCII equivalents, drop
    // control chars, and clamp to 99 characters (the documented limit).
    static String sanitizeDescription(String raw) {
        if (raw == null || raw.isBlank()) return "Odenis";
        String s = raw
                .replace('Ə', 'E').replace('ə', 'e')
                .replace('Ş', 'S').replace('ş', 's')
                .replace('Ç', 'C').replace('ç', 'c')
                .replace('Ğ', 'G').replace('ğ', 'g')
                .replace('İ', 'I').replace('ı', 'i')
                .replace('Ö', 'O').replace('ö', 'o')
                .replace('Ü', 'U').replace('ü', 'u')
                .replace('—', '-').replace('–', '-')
                .replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'')
                .replace('…', '.');
        // Strip any remaining non-ASCII or control character.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F) sb.append(c);
            else sb.append(' ');
        }
        s = sb.toString().replaceAll("\\s+", " ").trim();
        if (s.length() > 99) s = s.substring(0, 99).trim();
        return s.isEmpty() ? "Odenis" : s;
    }

    /**
     * Step 1: Create an order at Kapital Bank.
     * Returns orderId, order password, and the HPP redirect URL.
     */
    public CreateInvoiceResult createOrder(double amount, String description) {
        String safeDescription = sanitizeDescription(description);
        Map<String, Object> orderBody = new HashMap<>();
        orderBody.put("typeRid", "Order_SMS");
        orderBody.put("amount", String.format(java.util.Locale.US, "%.2f", amount));
        orderBody.put("currency", "AZN");
        orderBody.put("language", "az");
        orderBody.put("description", safeDescription);
        // NOTE: do NOT include `initiationEnvKind` on the create-order call.
        // It belongs to the `setSrcToken` flow (saved-card recurring) and
        // adding it here makes the HPP reject the request with
        // ServiceError/ApeError. The original PayriffService (e177a05) did
        // not send this field; commit 227da51 introduced it by mistake and
        // broke /order for everyone.
        orderBody.put("hppRedirectUrl", appBaseUrl + "/api/payment/callback");

        Map<String, Object> body = Map.of("order", orderBody);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders());

        ResponseEntity<JsonNode> response;
        try {
            response = restTemplate.exchange(
                    baseUrl() + "/order",
                    HttpMethod.POST,
                    entity,
                    JsonNode.class
            );
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // Without this log the only signal of an ApeError is the raw
            // 500 stack — the request body (description, amount, redirect
            // URL) stays hidden, so you can't tell if it was sanitisation,
            // a bad redirect, or genuine sandbox flakiness.
            log.error("Kapital Bank /order failed: status={} body={} sentDescription={} sentAmount={}",
                    e.getStatusCode(), e.getResponseBodyAsString(),
                    safeDescription, orderBody.get("amount"));
            throw new RuntimeException("Ödəniş provayderi xətası: " + e.getResponseBodyAsString(), e);
        }

        JsonNode json = response.getBody();
        if (json == null || !json.has("order")) {
            String msg = json != null ? json.path("errorDescription").asText("Unknown error") : "No response";
            throw new RuntimeException("Kapital Bank error: " + msg);
        }

        JsonNode order = json.path("order");
        String orderId = order.path("id").asText();
        String hppUrl = order.path("hppUrl").asText();
        String pwd = order.path("password").asText();

        if (orderId.isBlank()) {
            throw new RuntimeException("Kapital Bank returned empty orderId. Response: " + json);
        }

        log.info("Kapital Bank order created: id={}", orderId);

        String paymentUrl = hppUrl + "?id=" + orderId + "&password=" + pwd;
        return new CreateInvoiceResult(orderId, pwd, paymentUrl);
    }

    /**
     * Step 2 (optional): Attach a saved card token to the order before redirecting.
     * Used for recurring/saved-card payments (storedId comes from a previous transaction).
     * For Browser-initiated payments: initiationEnvKind = "Browser".
     * For Server-initiated (MIT/recurring): initiationEnvKind = "Server".
     */
    public void setSrcToken(String orderId, String orderPassword, Long storedId) {
        Map<String, Object> orderPart = Map.of("initiationEnvKind", "Browser");
        Map<String, Object> body;
        if (storedId != null) {
            body = Map.of("order", orderPart, "token", Map.of("storedId", storedId));
        } else {
            body = Map.of("order", orderPart);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders());

        String url = baseUrl() + "/order/" + orderId + "/set-src-token?password=" + orderPassword;
        ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.POST, entity, JsonNode.class);

        JsonNode json = response.getBody();
        log.info("Kapital Bank set-src-token response for orderId={}: status={}",
                orderId,
                json != null ? json.path("order").path("status").asText("?") : "null");
    }

    /**
     * Step 3 check: Query the order status.
     * FullyPaid → payment successful.
     */
    public String getOrderStatus(String orderId) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                baseUrl() + "/order/" + orderId + "?tranDetailLevel=2&tokenDetailLevel=2&orderDetailLevel=2",
                HttpMethod.GET,
                entity,
                JsonNode.class
        );

        JsonNode json = response.getBody();
        log.info("Kapital Bank getOrderStatus raw response for {}: {}", orderId, json);

        if (json == null || !json.has("order")) {
            log.warn("Kapital Bank getOrderStatus unexpected response for {}: {}", orderId, json);
            return "UNKNOWN";
        }

        String status = json.path("order").path("status").asText("UNKNOWN");
        log.info("Kapital Bank status for orderId {}: {}", orderId, status);
        return status;
    }
}
