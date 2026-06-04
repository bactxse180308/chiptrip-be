package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.trip.dto.request.CreateTripExpenseRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripExpenseResponse;

import java.util.List;

public interface TripExpenseService {
    List<TripExpenseResponse> getExpensesByTrip(Long tripId, Long userId);
    TripExpenseResponse createExpense(Long tripId, Long userId, CreateTripExpenseRequest request);
    void deleteExpense(Long tripId, Long expenseId, Long userId);
}
