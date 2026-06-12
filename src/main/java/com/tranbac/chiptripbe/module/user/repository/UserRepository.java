package com.tranbac.chiptripbe.module.user.repository;

import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByOauthProviderAndOauthProviderId(com.tranbac.chiptripbe.common.enums.OAuthProvider provider, String oauthProviderId);

    List<User> findAllByRole_NameAndIsActiveTrue(String roleName);

    @Query("SELECT YEAR(u.createdAt), MONTH(u.createdAt), DAY(u.createdAt), COUNT(u) " +
           "FROM User u WHERE u.createdAt >= :from AND u.createdAt <= :to " +
           "GROUP BY YEAR(u.createdAt), MONTH(u.createdAt), DAY(u.createdAt) " +
           "ORDER BY YEAR(u.createdAt), MONTH(u.createdAt), DAY(u.createdAt)")
    List<Object[]> countRegistrationsByDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Trừ 1 credit nếu còn > 0. Trả về số dòng affected: 1 = thành công, 0 = đã hết. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.aiCredits = u.aiCredits - 1 " +
           "WHERE u.id = :userId AND u.aiCredits > 0")
    int deductCreditIfAvailable(@Param("userId") Long userId);

    @Query("SELECT u.aiCredits FROM User u WHERE u.id = :userId")
    Integer findAiCreditsById(@Param("userId") Long userId);

    /** Cộng credits atomic (nạp tiền qua webhook). Trả số dòng affected. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.aiCredits = u.aiCredits + :amount WHERE u.id = :userId")
    int addCredits(@Param("userId") Long userId, @Param("amount") int amount);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.role = :premiumRole " +
           "WHERE u.id = :userId AND u.role <> :adminRole")
    int upgradeToPremiumUnlessAdmin(@Param("userId") Long userId,
                                    @Param("premiumRole") Role premiumRole,
                                    @Param("adminRole") Role adminRole);
}
