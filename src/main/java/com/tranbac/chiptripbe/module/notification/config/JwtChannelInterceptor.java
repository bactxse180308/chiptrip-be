package com.tranbac.chiptripbe.module.notification.config;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.common.security.JwtProvider;
import com.tranbac.chiptripbe.common.security.UserDetailsServiceImpl;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Collection;
import java.util.Collections;

/**
 * Xác thực JWT cho WebSocket STOMP.
 * - CONNECT: validate Bearer token, gắn Principal + authorities. Principal.getName()
 *   = String.valueOf(userId) để khớp key cho convertAndSendToUser.
 * - SUBSCRIBE: chặn các destination cần quyền (vd /topic/support chỉ cho ADMIN).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtChannelInterceptor implements ChannelInterceptor {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    private static final String ADMIN_SUPPORT_TOPIC = "/topic/support";

    private final JwtProvider jwtProvider;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        StompCommand command = accessor.getCommand();
        if (command == null) return message;

        switch (command) {
            case CONNECT -> handleConnect(accessor);
            case SUBSCRIBE -> handleSubscribe(accessor);
            default -> { /* các frame khác không cần check */ }
        }
        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw AppException.unauthorized("Thiếu Authorization header");
        }
        String token = authHeader.substring(7);

        Long userId;
        String email;
        try {
            userId = jwtProvider.getUserId(token);
            email = jwtProvider.getEmail(token);
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("WS JWT invalid: {}", ex.getClass().getSimpleName());
            throw AppException.unauthorized("Token không hợp lệ");
        }

        UserPrincipal principal = (UserPrincipal) userDetailsService.loadUserByUsername(email);
        if (!principal.getId().equals(userId)) {
            throw AppException.unauthorized("Token không khớp với user");
        }

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                new WsUserPrincipal(principal.getId()), null, principal.getAuthorities());
        accessor.setUser(auth);
        log.debug("WS CONNECT authenticated userId={}", userId);
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) return;

        if (destination.startsWith(ADMIN_SUPPORT_TOPIC)) {
            if (!hasAuthority(accessor.getUser(), ADMIN_AUTHORITY)) {
                log.warn("WS SUBSCRIBE denied to {} for non-admin", destination);
                throw AppException.forbidden("Không có quyền nghe kênh này");
            }
        }
    }

    private boolean hasAuthority(Principal principal, String authority) {
        if (!(principal instanceof UsernamePasswordAuthenticationToken auth)) return false;
        Collection<? extends GrantedAuthority> authorities =
                auth.getAuthorities() == null ? Collections.emptyList() : auth.getAuthorities();
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals(authority) || a.equals(new SimpleGrantedAuthority(authority).getAuthority()));
    }

    /** Principal wrapper để getName() trả userId (key cho user destination). */
    private record WsUserPrincipal(Long id) implements Principal {
        @Override public String getName() { return String.valueOf(id); }
    }
}
