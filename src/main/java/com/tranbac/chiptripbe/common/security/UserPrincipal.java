package com.tranbac.chiptripbe.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final boolean active;
    private final List<SimpleGrantedAuthority> authorities;

    public UserPrincipal(Long id, String email, String passwordHash, boolean active, String roleName) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.active = active;
        this.authorities = List.of(new SimpleGrantedAuthority(roleName));
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isEnabled() { return active; }

    // Used in @PreAuthorize("authentication.principal.toString() == #userId.toString()")
    @Override public String toString() { return id.toString(); }
}