package com.tranbac.chiptripbe.module.user.repository;

import com.tranbac.chiptripbe.module.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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
    @Query("UPDATE User u SET u.aiCredits = CASE WHEN u.aiCredits > 0 THEN u.aiCredits - 1 ELSE 0 END, " +
           "u.aiCreditUnits = COALESCE(u.aiCreditUnits, u.aiCredits * 100) - 100 " +
           "WHERE u.id = :userId AND COALESCE(u.aiCreditUnits, u.aiCredits * 100) >= 100")
    int deductCreditIfAvailable(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.aiCreditUnits = COALESCE(u.aiCreditUnits, u.aiCredits * 100) - :units " +
           "WHERE u.id = :userId AND COALESCE(u.aiCreditUnits, u.aiCredits * 100) >= :units")
    int deductCreditUnitsIfAvailable(@Param("userId") Long userId, @Param("units") int units);

    @Query("SELECT u.aiCredits FROM User u WHERE u.id = :userId")
    Integer findAiCreditsById(@Param("userId") Long userId);

    // ─── Trial credit (CREDIT_PREMIUM_SPEC.md Mục 5.2) ───────────────────────

    /** Cấp trial hằng ngày: SET=1 (không cộng dồn), idempotent, atomic. Gọi trước khi deduct. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.trialCreditBalance = 1, u.trialCreditDate = :today " +
           "WHERE u.id = :userId AND (u.trialCreditDate IS NULL OR u.trialCreditDate <> :today)")
    int grantDailyTrialIfNeeded(@Param("userId") Long userId, @Param("today") LocalDate today);

    /** Trừ 1 trial — CHỈ khi paid = 0 (enforce luật "paid trước, trial sau" ngay ở DB). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.trialCreditBalance = u.trialCreditBalance - 1 " +
           "WHERE u.id = :userId AND u.trialCreditBalance >= 1 " +
           "AND COALESCE(u.aiCreditUnits, u.aiCredits * 100) = 0")
    int deductTrialCredit(@Param("userId") Long userId);

    @Query("SELECT u.trialCreditBalance FROM User u WHERE u.id = :userId")
    Integer findTrialBalanceById(@Param("userId") Long userId);

    @Query("SELECT u.trialCreditDate FROM User u WHERE u.id = :userId")
    LocalDate findTrialDateById(@Param("userId") Long userId);

    @Query("SELECT COALESCE(u.aiCreditUnits, u.aiCredits * 100) FROM User u WHERE u.id = :userId")
    Integer findAiCreditUnitsById(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.aiCredits = :credits WHERE u.id = :userId")
    int syncWholeCredits(@Param("userId") Long userId, @Param("credits") int credits);

    /** Cộng credits atomic (nạp tiền qua webhook). Trả số dòng affected. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.aiCredits = u.aiCredits + :amount, " +
           "u.aiCreditUnits = COALESCE(u.aiCreditUnits, u.aiCredits * 100) + (:amount * 100) " +
           "WHERE u.id = :userId")
    int addCredits(@Param("userId") Long userId, @Param("amount") int amount);
}
