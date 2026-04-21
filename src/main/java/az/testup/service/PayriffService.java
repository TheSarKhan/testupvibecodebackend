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
public class PayriffService {

    private static final Logger log = LoggerFactory.getLogger(PayriffService.class);

    @Value("${kapitalbank.username}")
    private String kbUsername;

    @Value("${kapitalbank.password}")
    private String kbPassword;

    @Value("${kapitalbank.production:false}")
    private boolean production;

    @Value("${app.base-url}")
    private String appBaseUrl;

    private final RestTemplate restTemplate;

    public record CreateInvoiceResult(String orderId, String paymentUrl) {}

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

    public CreateInvoiceResult createOrder(double amount, String description) {
        Map<String, Object> orderBody = new HashMap<>();
        orderBody.put("typeRid", "Order_SMS");
        orderBody.put("amount", String.format("%.2f", amount));
        orderBody.put("currency", "AZN");
        orderBody.put("language", "az");
        orderBody.put("description", description);
        orderBody.put("hppRedirectUrl", appBaseUrl + "/api/payment/callback");

        Map<String, Object> body = Map.of("order", orderBody);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders());

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                baseUrl() + "/order",
                HttpMethod.POST,
                entity,
                JsonNode.class
        );

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

        // Redirect URL: {hppUrl}?id={id}&password={password}
        String paymentUrl = hppUrl + "?id=" + orderId + "&password=" + pwd;
        return new CreateInvoiceResult(orderId, paymentUrl);
    }

    public String getOrderStatus(String orderId) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                baseUrl() + "/order/" + orderId,
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
