package com.tranbac.chiptripbe.common.config;

import com.tranbac.chiptripbe.common.enums.RoleName;
import com.tranbac.chiptripbe.module.user.entity.Role;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.RoleRepository;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ADMIN_EMAIL:}")
    private String adminEmail;

    @Value("${ADMIN_PASSWORD:}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(String... args) {
        seedRoles();
        seedAdmin();
    }

    private void seedRoles() {
        ensureRole(RoleName.USER,    "Người dùng thông thường");
        ensureRole(RoleName.PREMIUM, "Người dùng trả phí");
        ensureRole(RoleName.ADMIN,   "Quản trị viên hệ thống");
    }

    private void ensureRole(String name, String desc) {
        roleRepository.findByName(name).orElseGet(() ->
                roleRepository.save(Role.builder()
                        .name(name)
                        .description(desc)
                        .build()));
    }

    private void seedAdmin() {
        if (!StringUtils.hasText(adminEmail) || !StringUtils.hasText(adminPassword)) {
            log.info("ADMIN_EMAIL/ADMIN_PASSWORD not set — skipping admin seed");
            return;
        }
        if (userRepository.existsByEmail(adminEmail)) return;

        Role adminRole = roleRepository.findByName(RoleName.ADMIN).orElseThrow();
        User admin = User.builder()
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .fullName("Quản trị hệ thống")
                .isActive(true)
                .emailVerified(true)
                .aiCredits(9999)
                .role(adminRole)
                .build();
        userRepository.save(admin);
        log.info("Admin account seeded: {}", adminEmail);
    }
}