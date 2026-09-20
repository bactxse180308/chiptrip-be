package com.tranbac.chiptripbe.module.notification.config;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.common.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtChannelInterceptorTest {
    private final JwtProvider jwt = mock(JwtProvider.class);
    private final UserDetailsServiceImpl users = mock(UserDetailsServiceImpl.class);
    private final WebSocketAccountGuard guard = mock(WebSocketAccountGuard.class);
    private final JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwt, users, guard);

    @Test void clientCannotSendToServerBrokerTopic() {
        var headers = StompHeaderAccessor.create(StompCommand.SEND);
        headers.setSessionId("s1");
        headers.setDestination("/topic/support");
        headers.setUser(new UsernamePasswordAuthenticationToken("1", null, List.of()));
        assertThrows(AppException.class, () -> interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null));
    }

    @Test void disabledAccountCannotConnect() {
        when(jwt.getUserId("token")).thenReturn(1L);
        when(jwt.getEmail("token")).thenReturn("u@example.com");
        when(users.loadUserByUsername("u@example.com"))
                .thenReturn(new UserPrincipal(1L, "u@example.com", "hash", false, "ROLE_USER"));
        var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setLeaveMutable(true);
        headers.setSessionId("s1");
        headers.setNativeHeader("Authorization", "Bearer token");
        assertThrows(AppException.class, () -> interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null));
    }

    @Test void subscriptionsAreAllowlistedAndRequireAnActiveSession() {
        when(guard.isActive("s1")).thenReturn(true);
        for (String destination : List.of("/user/queue/messages", "/user/queue/notifications",
                "/user/queue/trip-generation", "/user/queue/trip-enrichment")) {
            assertDoesNotThrow(() -> subscribe(destination, "ROLE_USER"));
        }
        for (String destination : List.of("/topic/support", "/topic/support-extra", "/queue/messages-user-other",
                "/user/2/queue/messages", "/unknown")) {
            assertThrows(AppException.class, () -> subscribe(destination, "ROLE_USER"));
        }
        assertDoesNotThrow(() -> subscribe("/topic/support", "ROLE_ADMIN"));
        when(guard.isActive("s1")).thenReturn(false);
        assertThrows(AppException.class, () -> subscribe("/user/queue/messages", "ROLE_USER"));
    }

    private void subscribe(String destination, String authority) {
        var headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        headers.setSessionId("s1");
        headers.setDestination(destination);
        headers.setUser(new UsernamePasswordAuthenticationToken("1", null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(authority))));
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null);
    }
}
