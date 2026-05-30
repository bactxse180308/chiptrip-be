package com.tranbac.chiptripbe.module.auth.repository;

import com.tranbac.chiptripbe.module.auth.entity.OtpCode;
import com.tranbac.chiptripbe.module.auth.entity.OtpCode.Purpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OtpCodeRepository extends JpaRepository<OtpCode, Long> {

    Optional<OtpCode> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, Purpose purpose);

    @Modifying
    @Query("UPDATE OtpCode o SET o.used = true WHERE o.email = :email AND o.purpose = :purpose AND o.used = false")
    void invalidateAllByEmailAndPurpose(@Param("email") String email, @Param("purpose") Purpose purpose);

    @Modifying
    @Query("DELETE FROM OtpCode o WHERE o.expiresAt < :now")
    void deleteExpiredCodes(@Param("now") LocalDateTime now);
}
