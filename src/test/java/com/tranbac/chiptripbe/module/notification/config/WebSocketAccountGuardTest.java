package com.tranbac.chiptripbe.module.notification.config;

import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketAccountGuardTest {
    @Test void existingSubscriptionStopsReceivingWhenAccountDisabled() {
        var users = mock(UserRepository.class);
        var guard = new WebSocketAccountGuard(users);
        guard.register("session", 1L);
        when(users.existsByIdAndIsActiveTrue(1L)).thenReturn(true);
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId("session");
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        assertSame(message, guard.preSend(message, null));
        when(users.existsByIdAndIsActiveTrue(1L)).thenReturn(false);
        assertNull(guard.preSend(message, null));
        when(users.existsByIdAndIsActiveTrue(1L)).thenReturn(true);
        guard.disconnected(new SessionDisconnectEvent(this, message, "session", CloseStatus.NORMAL));
        assertNull(guard.preSend(message, null));
    }

    @Test void heartbeatIsNotTreatedAsApplicationData() {
        var guard = new WebSocketAccountGuard(mock(UserRepository.class));
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.HEARTBEAT);
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        assertSame(message, guard.preSend(message, null));
    }
}
