package com.tranbac.chiptripbe.module.user.repository;

import com.tranbac.chiptripbe.module.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByOauthProviderAndOauthProviderId(com.tranbac.chiptripbe.common.enums.OAuthProvider provider, String oauthProviderId);

    @Query("SELECT YEAR(u.createdAt), MONTH(u.createdAt), DAY(u.createdAt), COUNT(u) " +
           "FROM User u WHERE u.createdAt >= :from AND u.createdAt <= :to " +
           "GROUP BY YEAR(u.createdAt), MONTH(u.createdAt), DAY(u.createdAt) " +
           "ORDER BY YEAR(u.createdAt), MONTH(u.createdAt), DAY(u.createdAt)")
    List<Object[]> countRegistrationsByDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}