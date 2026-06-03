package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.trip.dto.request.CreateChecklistItemRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateChecklistItemRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;

import java.util.List;

public interface ChecklistService {

    List<TripDetailResponse.ChecklistItemDetail> getChecklist(Long userId, Long tripId);

    TripDetailResponse.ChecklistItemDetail addChecklistItem(Long userId, Long tripId, CreateChecklistItemRequest request);

    TripDetailResponse.ChecklistItemDetail updateChecklistItem(Long userId, Long tripId, Long itemId, UpdateChecklistItemRequest request);

    TripDetailResponse.ChecklistItemDetail toggleChecklistItem(Long userId, Long tripId, Long itemId);

    void deleteChecklistItem(Long userId, Long tripId, Long itemId);
}
