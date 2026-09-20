package com.tranbac.chiptripbe.common.security;

import com.tranbac.chiptripbe.common.config.SecurityConfig;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(JwtSecurityChainTest.Config.class)
@WebAppConfiguration
class JwtSecurityChainTest {
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired UserDetailsServiceImpl users;
    private MockMvc mvc;
    private String bearer;

    @BeforeEach void setUp() {
        reset(users);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        bearer = "Bearer " + jwt.generateAccessToken(1L, "user@example.com", "ROLE_USER");
    }

    @Test void previouslyIssuedTokenStopsWorkingAfterAccountDisable() throws Exception {
        account(true);
        mvc.perform(get("/api/v1/test").header("Authorization", bearer)).andExpect(status().isOk());
        account(false);
        mvc.perform(get("/api/v1/test").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/test").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/trips/1/public").header("Authorization", bearer)).andExpect(status().isOk());
    }

    @Test void missingDeletedAndMalformedCredentialsCannotAccessProtectedEndpoints() throws Exception {
        mvc.perform(get("/api/v1/test")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/test").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        when(users.loadUserByUsername("user@example.com")).thenThrow(new UsernameNotFoundException("deleted"));
        mvc.perform(get("/api/v1/test").header("Authorization", bearer)).andExpect(status().isUnauthorized());
    }

    @Test void enabledUserStillCannotAccessAdminEndpoints() throws Exception {
        account(true);
        mvc.perform(get("/api/v1/admin/test").header("Authorization", bearer)).andExpect(status().isForbidden());
    }

    private void account(boolean enabled) {
        when(users.loadUserByUsername("user@example.com"))
                .thenReturn(new UserPrincipal(1L, "user@example.com", "hash", enabled, "ROLE_USER"));
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtAuthFilter.class, CorsProperties.class,
            CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class, Endpoints.class})
    static class Config {
        @Bean UserDetailsServiceImpl users() { return mock(UserDetailsServiceImpl.class); }
        @Bean JwtProvider jwt() {
            var properties = new JwtProperties();
            properties.setSecret("test-only-signing-key-not-for-production-use");
            properties.setAccessTokenExpiryMs(60000);
            return new JwtProvider(properties);
        }
    }

    @RestController
    static class Endpoints {
        @GetMapping({"/api/v1/test", "/api/v1/admin/test", "/api/v1/trips/1/public"})
        String read() { return "ok"; }
    }
}
