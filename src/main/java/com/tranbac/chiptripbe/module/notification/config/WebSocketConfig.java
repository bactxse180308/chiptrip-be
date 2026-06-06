package com.tranbac.chiptripbe.module.notification.config;

import com.tranbac.chiptripbe.common.security.CorsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Cấu hình WebSocket STOMP cho push notification realtime.
 *
 * Lưu ý vận hành:
 * - SimpleBroker là in-memory; khi scale nhiều instance cần broker external
 *   (RabbitMQ relay / Redis pub-sub). MVP 1 instance chưa cần.
 * - Client connect bằng access token JWT 15 phút; phía FE phải reconnect với
 *   token mới khi refresh token (xử lý ở client, không tự làm ở BE).
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;
    private final CorsProperties corsProperties;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = corsProperties.getAllowedOrigins().toArray(String[]::new);
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins.length == 0 ? new String[]{"*"} : origins)
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtChannelInterceptor);
    }
}
