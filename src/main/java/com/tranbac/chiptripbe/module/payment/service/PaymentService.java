package com.tranbac.chiptripbe.module.payment.service;

import com.tranbac.chiptripbe.module.payment.dto.SepayWebhookPayload;

public interface PaymentService {

    void processWebhook(SepayWebhookPayload payload);
}
