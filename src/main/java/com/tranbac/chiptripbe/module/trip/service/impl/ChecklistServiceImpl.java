package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.trip.dto.request.CreateChecklistItemRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateChecklistItemRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.entity.ChecklistItem;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.repository.ChecklistItemRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.ChecklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ChecklistServiceImpl implements ChecklistService {

    private final ChecklistItemRepository checklistItemRepository;
    private final TripRepository tripRepository;

    @Override
    @Transactional(readOnly = true)
    public List<TripDetailResponse.ChecklistItemDetail> getChecklist(Long userId, Long tripId) {
        findTripAndValidateOwnership(tripId, userId);
        return checklistItemRepository.findByTripIdOrderByDisplayOrder(tripId).stream()
                .map(this::toDetail)
                .toList();
    }

    @Override
    @Transactional
    public TripDetailResponse.ChecklistItemDetail addChecklistItem(Long userId, Long tripId,
                                                                     CreateChecklistItemRequest request) {
        Trip trip = findTripAndValidateOwnership(tripId, userId);

        int maxOrder = trip.getChecklist().stream()
                .mapToInt(ChecklistItem::getDisplayOrder)
                .max().orElse(0);

        ChecklistItem item = ChecklistItem.builder()
                .trip(trip)
                .category(request.getCategory())
                .name(request.getName())
                .isChecked(false)
                .displayOrder(maxOrder + 1)
                .build();

        item = checklistItemRepository.save(item);
        log.info("Added checklist item id={} to tripId={}", item.getId(), tripId);
        return toDetail(item);
    }

    @Override
    @Transactional
    public TripDetailResponse.ChecklistItemDetail updateChecklistItem(Long userId, Long tripId,
                                                                       Long itemId,
                                                                       UpdateChecklistItemRequest request) {
        findTripAndValidateOwnership(tripId, userId);

        ChecklistItem item = checklistItemRepository.findByIdAndTripUserId(itemId, userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy item"));

        if (request.getName() != null) item.setName(request.getName());
        if (request.getCategory() != null) item.setCategory(request.getCategory());

        item = checklistItemRepository.save(item);
        log.info("Updated checklist item id={}", itemId);
        return toDetail(item);
    }

    @Override
    @Transactional
    public TripDetailResponse.ChecklistItemDetail toggleChecklistItem(Long userId, Long tripId, Long itemId) {
        findTripAndValidateOwnership(tripId, userId);

        ChecklistItem item = checklistItemRepository.findByIdAndTripUserId(itemId, userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy item"));

        item.setIsChecked(!item.getIsChecked());
        item = checklistItemRepository.save(item);
        log.info("Toggled checklist item id={} to isChecked={}", itemId, item.getIsChecked());
        return toDetail(item);
    }

    @Override
    @Transactional
    public void deleteChecklistItem(Long userId, Long tripId, Long itemId) {
        findTripAndValidateOwnership(tripId, userId);

        ChecklistItem item = checklistItemRepository.findByIdAndTripUserId(itemId, userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy item"));

        checklistItemRepository.delete(item);
        log.info("Deleted checklist item id={}", itemId);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private Trip findTripAndValidateOwnership(Long tripId, Long userId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        if (!trip.getUser().getId().equals(userId)) {
            throw AppException.forbidden("Bạn không có quyền với chuyến đi này");
        }
        return trip;
    }

    private TripDetailResponse.ChecklistItemDetail toDetail(ChecklistItem item) {
        return TripDetailResponse.ChecklistItemDetail.builder()
                .id(item.getId())
                .category(item.getCategory() != null ? item.getCategory().name() : null)
                .name(item.getName())
                .isChecked(item.getIsChecked())
                .displayOrder(item.getDisplayOrder())
                .build();
    }
}
