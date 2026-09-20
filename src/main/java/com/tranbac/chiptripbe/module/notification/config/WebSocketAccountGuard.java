package com.tranbac.chiptripbe.module.notification.config;

import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.concurrent.ConcurrentHashMap;

/** Recheck delivery, not just CONNECT: existing subscriptions must not bypass account disable. */
@Component
@RequiredArgsConstructor
public class WebSocketAccountGuard implements ChannelInterceptor {
    private final UserRepository users;
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();

    public void register(String sessionId, Long userId) {
        sessions.put(sessionId, userId);
    }

    public boolean isActive(String sessionId) {
        Long userId = sessionId == null ? null : sessions.get(sessionId);
        return userId != null && users.existsByIdAndIsActiveTrue(userId);
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        sessions.remove(event.getSessionId());
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
            return message;
        }
        return isActive(SimpMessageHeaderAccessor.getSessionId(message.getHeaders())) ? message : null;
    }
}
