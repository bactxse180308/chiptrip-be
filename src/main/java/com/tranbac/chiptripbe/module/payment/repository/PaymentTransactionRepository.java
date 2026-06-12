package com.tranbac.chiptripbe.module.payment.repository;

import com.tranbac.chiptripbe.module.payment.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    boolean existsBySepayTransactionId(Long sepayTransactionId);
}
