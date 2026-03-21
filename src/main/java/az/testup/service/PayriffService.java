package az.testup.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PayriffService {

    private static final Logger log = LoggerFactory.getLogger(PayriffService.class);

    private static final String V3_BASE = "https://api.payriff.com/api/v3";

    @Value("${payriff.secret-key}")
    private String secretKey;

    @Value("${app.base-url}")
    private String appBaseUrl;

    private final RestTemplate restTemplate;

    public record CreateInvoiceResult(String orderId, String paymentUrl) {}

    public CreateInvoiceResult createOrder(double amount, String description) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", secretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("currency", "AZN");
        body.put("description", description);
        body.put("language", "AZ");
        body.put("operation", "PURCHASE");
        body.put("approveUrl", appBaseUrl + "/odenis/ugurlu");
        body.put("cancelUrl", appBaseUrl + "/odenis/legv");
        body.put("declineUrl", appBaseUrl + "/odenis/red");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                V3_BASE + "/orders",
                HttpMethod.POST,
                entity,
                JsonNode.class
        );

        JsonNode json = response.getBody();
        if (json == null || !"00000".equals(json.path("code").asText())) {
            String msg = json != null ? json.path("message").asText("Unknown error") : "No response";
            throw new RuntimeException("Payriff error: " + msg);
        }

        JsonNode payload = json.path("payload");
        String orderId = payload.path("orderId").asText();
        String paymentUrl = payload.path("paymentUrl").asText();
        if (orderId.isBlank()) {
            throw new RuntimeException("Payriff returned empty orderId. Payload: " + payload);
        }
        return new CreateInvoiceResult(orderId, paymentUrl);
    }

    public String getOrderStatus(String orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", secretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                V3_BASE + "/orders/" + orderId,
                HttpMethod.GET,
                entity,
                JsonNode.class
        );

        JsonNode json = response.getBody();
        log.info("Payriff getOrderStatus raw response for {}: {}", orderId, json);
        if (json == null || !"00000".equals(json.path("code").asText())) {
            log.warn("Payriff getOrderStatus bad code for {}: {}", orderId, json);
            return "UNKNOWN";
        }

        JsonNode payload = json.path("payload");
        String status = payload.path("paymentStatus").asText(
                payload.path("status").asText("UNKNOWN")
        );
        log.info("Payriff status for orderId {}: {}", orderId, status);
        return status;
    }
}
