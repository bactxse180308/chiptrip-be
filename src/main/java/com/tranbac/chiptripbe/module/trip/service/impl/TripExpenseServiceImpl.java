package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.trip.dto.request.CreateTripExpenseRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripExpenseResponse;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripExpense;
import com.tranbac.chiptripbe.module.trip.repository.TripExpenseRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripMemberRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripExpenseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripExpenseServiceImpl implements TripExpenseService {

    private final TripExpenseRepository tripExpenseRepository;
    private final TripRepository tripRepository;
    private final TripMemberRepository tripMemberRepository;

    @Override
    public List<TripExpenseResponse> getExpensesByTrip(Long tripId, Long userId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        verifyAccess(trip, userId);
        
        List<TripExpense> expenses = tripExpenseRepository.findByTripIdOrderByCreatedAtDesc(tripId);
        return expenses.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public TripExpenseResponse createExpense(Long tripId, Long userId, CreateTripExpenseRequest request) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));

        verifyAccess(trip, userId);

        String splitAmongStr = request.getSplitAmong() != null 
            ? String.join(",", request.getSplitAmong()) 
            : "";

        TripExpense expense = TripExpense.builder()
                .trip(trip)
                .title(request.getTitle())
                .amount(request.getAmount())
                .category(request.getCategory())
                .paidBy(request.getPaidBy())
                .splitAmong(splitAmongStr)
                .build();

        expense = tripExpenseRepository.save(expense);
        log.info("User {} created expense {} for trip {}", userId, expense.getId(), tripId);
        return toResponse(expense);
    }

    @Override
    @Transactional
    public void deleteExpense(Long tripId, Long expenseId, Long userId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));

        verifyAccess(trip, userId);

        TripExpense expense = tripExpenseRepository.findById(expenseId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chi phí"));

        if (!expense.getTrip().getId().equals(tripId)) {
            throw AppException.badRequest("Chi phí không thuộc chuyến đi này");
        }

        tripExpenseRepository.delete(expense);
        log.info("User {} deleted expense {} from trip {}", userId, expenseId, tripId);
    }

    private void verifyAccess(Trip trip, Long userId) {
        if (trip.getUser().getId().equals(userId)) {
            return;
        }
        boolean isMember = tripMemberRepository.existsByTripIdAndUserId(trip.getId(), userId);
        if (!isMember) {
            throw AppException.forbidden("Bạn không có quyền với chuyến đi này");
        }
    }

    private TripExpenseResponse toResponse(TripExpense expense) {
        List<String> splitAmong = expense.getSplitAmong() != null && !expense.getSplitAmong().isEmpty()
                ? Arrays.asList(expense.getSplitAmong().split(","))
                : List.of();

        return TripExpenseResponse.builder()
                .id(expense.getId())
                .tripId(expense.getTrip().getId())
                .title(expense.getTitle())
                .amount(expense.getAmount())
                .category(expense.getCategory())
                .paidBy(expense.getPaidBy())
                .splitAmong(splitAmong)
                .createdAt(expense.getCreatedAt())
                .build();
    }
}
