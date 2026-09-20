package com.tranbac.chiptripbe.module.user.service.impl;

import com.tranbac.chiptripbe.module.user.entity.*;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import com.tranbac.chiptripbe.support.SecurityPersistenceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(SecurityPersistenceConfig.class)
class CreditPersistenceTest {
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;

    @Test void grantMigratesLegacyNullUnitsUsingWholeBalance() {
        var user = seed(0);
        new JdbcTemplate(dataSource).update("UPDATE users SET ai_credit_units = NULL, ai_credits = 2 WHERE id = ?", user.getId());
        tx().executeWithoutResult(s -> assertEquals(1, users.addCredits(user.getId(), 1)));
        var actual = users.findById(user.getId()).orElseThrow();
        assertEquals(300, actual.getAiCreditUnits());
        assertEquals(3, actual.getAiCredits());
    }

    @Test void simultaneousGrantAndSpendPreserveBothChanges() throws Exception {
        var user = seed(175);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var jobs = new ArrayList<Future<?>>();
            for (boolean grant : new boolean[]{true, false}) jobs.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                tx().executeWithoutResult(s -> assertEquals(1, grant
                        ? users.addCredits(user.getId(), 1) : users.deductCreditIfAvailable(user.getId())));
                return null;
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var job : jobs) job.get(20, TimeUnit.SECONDS);
        }
        var actual = users.findById(user.getId()).orElseThrow();
        assertEquals(175, actual.getAiCreditUnits());
        assertEquals(1, actual.getAiCredits());
    }

    @Test void grantKeepsFractionAndRepairsStaleWholeBalance() {
        for (int units : new int[]{0, 25, 50, 75, 100, 125, 250}) {
            var user = seed(units);
            tx().executeWithoutResult(s -> assertEquals(1, users.addCredits(user.getId(), 1)));
            var actual = users.findById(user.getId()).orElseThrow();
            assertEquals(units + 100, actual.getAiCreditUnits());
            assertEquals((units + 100) / 100, actual.getAiCredits());
        }
    }

    @Test void twoConcurrentGrantsDoNotLoseBalance() throws Exception {
        var user = seed(75);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            Callable<Void> grant = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                tx().executeWithoutResult(s -> assertEquals(1, users.addCredits(user.getId(), 1)));
                return null;
            };
            var first = pool.submit(grant);
            var second = pool.submit(grant);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        assertEquals(275, users.findById(user.getId()).orElseThrow().getAiCreditUnits());
    }

    @Test void profileSaveCannotOverwriteConcurrentGrant() throws Exception {
        var user = seed(75);
        var loaded = new CountDownLatch(1);
        var granted = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var profile = pool.submit(() -> tx().executeWithoutResult(s -> {
                var stale = users.findById(user.getId()).orElseThrow();
                loaded.countDown();
                try { assertTrue(granted.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                stale.setFullName("Changed after grant");
                users.save(stale);
            }));
            assertTrue(loaded.await(10, TimeUnit.SECONDS));
            try { tx().executeWithoutResult(s -> assertEquals(1, users.addCredits(user.getId(), 1))); }
            finally { granted.countDown(); }
            profile.get(20, TimeUnit.SECONDS);
        }
        var actual = users.findById(user.getId()).orElseThrow();
        assertEquals(175, actual.getAiCreditUnits());
        assertEquals("Changed after grant", actual.getFullName());
    }

    @Test void rollbackAndOverflowDoNotGrantCredits() {
        var user = seed(75);
        assertThrows(IllegalStateException.class, () -> tx().executeWithoutResult(s -> {
            users.addCredits(user.getId(), 1);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(75, users.findById(user.getId()).orElseThrow().getAiCreditUnits());
        var full = seed(Integer.MAX_VALUE);
        tx().executeWithoutResult(s -> assertEquals(0, users.addCredits(full.getId(), 1)));
        assertEquals(Integer.MAX_VALUE, users.findById(full.getId()).orElseThrow().getAiCreditUnits());
    }

    private TransactionTemplate tx() { return new TransactionTemplate(manager); }

    private User seed(int units) {
        User user = User.builder().email(UUID.randomUUID() + "@example.com").fullName("Test")
                .passwordHash("test-hash").aiCreditUnits(units).aiCredits(9)
                .oauthProvider(com.tranbac.chiptripbe.common.enums.OAuthProvider.GOOGLE).oauthProviderId(UUID.randomUUID().toString())
                .role(Role.builder().name("R_" + UUID.randomUUID()).build()).build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return users.save(user);
    }
}
