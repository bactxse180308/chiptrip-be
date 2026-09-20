package com.tranbac.chiptripbe.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthFilterTest {
    private final JwtProvider jwt = mock(JwtProvider.class);
    private final UserDetailsServiceImpl users = mock(UserDetailsServiceImpl.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwt, users);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void disabledAccountIsNotAuthenticatedByPreviouslyIssuedToken() throws Exception {
        run(false, false);
    }

    @Test void enabledAccountIsAuthenticated() throws Exception {
        run(true, true);
    }

    @Test void deletedAccountDoesNotTurnIntoServerError() throws Exception {
        var request = request();
        when(users.loadUserByUsername("user@example.com")).thenThrow(new UsernameNotFoundException("deleted"));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertNull(SecurityContextHolder.getContext().getAuthentication()));
    }

    private void run(boolean enabled, boolean authenticated) throws Exception {
        var request = request();
        when(users.loadUserByUsername("user@example.com"))
                .thenReturn(new UserPrincipal(1L, "user@example.com", "hash", enabled, "ROLE_USER"));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertEquals(authenticated, SecurityContextHolder.getContext().getAuthentication() != null));
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("Authorization", "Bearer old-token");
        when(jwt.isTokenValid("old-token")).thenReturn(true);
        when(jwt.getEmail("old-token")).thenReturn("user@example.com");
        return request;
    }
}
