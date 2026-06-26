package com.tranbac.chiptripbe.module.stats.service.impl;

import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.payment.entity.OrderStatus;
import com.tranbac.chiptripbe.module.payment.repository.PaymentOrderRepository;
import com.tranbac.chiptripbe.module.place.repository.PlaceReviewRepository;
import com.tranbac.chiptripbe.module.stats.dto.response.DashboardResponse;
import com.tranbac.chiptripbe.module.trip.repository.TripCommentRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripLikeRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private TripRepository tripRepository;
    @Mock private TripLikeRepository tripLikeRepository;
    @Mock private TripCommentRepository tripCommentRepository;
    @Mock private PlaceReviewRepository placeReviewRepository;
    @Mock private AiUsageRepository aiUsageRepository;
    @Mock private PaymentOrderRepository paymentOrderRepository;

    @InjectMocks
    private StatsServiceImpl service;

    @Test
    void getDashboard_readsPaidOrderStatsFromPaymentOrders() {
        when(userRepository.count()).thenReturn(13L);
        when(tripRepository.count()).thenReturn(25L);
        when(tripRepository.countByIsPublicTrue()).thenReturn(5L);
        when(tripLikeRepository.count()).thenReturn(2L);
        when(tripCommentRepository.count()).thenReturn(1L);
        when(placeReviewRepository.count()).thenReturn(2L);
        when(aiUsageRepository.aggregateForPeriod(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(new Object[]{new BigDecimal("2.00"), 42L}));
        when(paymentOrderRepository.countByStatus(OrderStatus.PAID)).thenReturn(3L);
        when(paymentOrderRepository.sumAmountVndByStatusAndPaidAtBetween(
                eq(OrderStatus.PAID),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(158_000L);

        DashboardResponse response = service.getDashboard();

        assertEquals(3L, response.getTotalOrders());
        assertEquals(new BigDecimal("158000"), response.getRevenueVndThisMonth());
        verify(paymentOrderRepository).countByStatus(OrderStatus.PAID);
        verify(paymentOrderRepository).sumAmountVndByStatusAndPaidAtBetween(
                eq(OrderStatus.PAID),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        );
    }
}
