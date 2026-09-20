package com.tranbac.chiptripbe.module.notification.config;

import com.tranbac.chiptripbe.common.security.*;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketChannelIntegrationTest {
    @Test void configuredChannelsRejectClientPublishAndBlockDeliveryAfterDisable() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            var inbound = context.getBean("clientInboundChannel", AbstractSubscribableChannel.class);
            var outbound = context.getBean("clientOutboundChannel", AbstractSubscribableChannel.class);
            var jwt = context.getBean(JwtProvider.class);
            var users = context.getBean(UserDetailsServiceImpl.class);
            var repository = context.getBean(UserRepository.class);
            when(jwt.getUserId("test-token")).thenReturn(1L);
            when(jwt.getEmail("test-token")).thenReturn("user@example.com");
            when(users.loadUserByUsername("user@example.com"))
                    .thenReturn(new UserPrincipal(1L, "user@example.com", "hash", true, "ROLE_USER"));
            when(repository.existsByIdAndIsActiveTrue(1L)).thenReturn(true);

            var connect = headers(StompCommand.CONNECT);
            connect.setNativeHeader("Authorization", "Bearer test-token");
            connect.setAcceptVersion("1.2");
            assertTrue(inbound.send(message(connect)));
            var user = connect.getUser();
            assertNotNull(user);

            for (String destination : new String[]{"/topic/support", "/queue/messages", "/user/queue/messages", "/app/chat"}) {
                var send = headers(StompCommand.SEND);
                send.setUser(user);
                send.setDestination(destination);
                assertThrows(MessageDeliveryException.class, () -> inbound.send(message(send)));
            }
            var subscribe = headers(StompCommand.SUBSCRIBE);
            subscribe.setUser(user);
            subscribe.setSubscriptionId("private");
            subscribe.setDestination("/user/queue/messages");
            assertTrue(inbound.send(message(subscribe)));

            var delivered = new LinkedBlockingQueue<Message<?>>();
            outbound.subscribe(message -> {
                if (org.springframework.messaging.simp.SimpMessageHeaderAccessor.getMessageType(message.getHeaders())
                        == org.springframework.messaging.simp.SimpMessageType.MESSAGE) delivered.add(message);
            });
            var push = headers(StompCommand.MESSAGE);
            push.setDestination("/queue/messages-usersession-test");
            push.setSubscriptionId("private");
            assertTrue(outbound.send(message(push)));
            assertNotNull(delivered.poll(5, TimeUnit.SECONDS));
            delivered.clear();
            when(repository.existsByIdAndIsActiveTrue(1L)).thenReturn(false);
            assertFalse(outbound.send(message(push)));
            assertNull(delivered.poll(100, TimeUnit.MILLISECONDS));
            assertThrows(MessageDeliveryException.class, () -> inbound.send(message(subscribe)));
        }
    }

    private static StompHeaderAccessor headers(StompCommand command) {
        var headers = StompHeaderAccessor.create(command);
        headers.setSessionId("session-test");
        headers.setLeaveMutable(true);
        return headers;
    }

    private static Message<byte[]> message(StompHeaderAccessor headers) {
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Configuration
    @Import({WebSocketConfig.class, JwtChannelInterceptor.class, WebSocketAccountGuard.class, CorsProperties.class})
    static class Config {
        @Bean JwtProvider jwt() { return mock(JwtProvider.class); }
        @Bean UserDetailsServiceImpl users() { return mock(UserDetailsServiceImpl.class); }
        @Bean UserRepository repository() { return mock(UserRepository.class); }
    }
}
