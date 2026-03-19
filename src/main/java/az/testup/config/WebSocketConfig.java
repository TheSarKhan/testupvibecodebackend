package az.testup.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // İki ön qoşma - /topic ümumi məlumatlar, /queue (və ya /user) şəxsi məlumatlar üçün
        config.enableSimpleBroker("/topic", "/queue");
        // Frontend-in arxaya atacağı mesajlar /app portundan girəcək
        config.setApplicationDestinationPrefixes("/app");
        // İstifadəçi özəl mesajlamaları üçün
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Frontendin qoşulacağı WebSocket ünvanı. SockJS dəstəkləyir
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
