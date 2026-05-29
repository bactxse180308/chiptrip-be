package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.ShareTokenResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerateResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripSummaryResponse;
import com.tranbac.chiptripbe.module.trip.entity.Activity;
import com.tranbac.chiptripbe.module.trip.entity.ChecklistItem;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.ChecklistItemRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TripServiceImpl implements TripService {

    private final TripRepository tripRepository;
    private final TripDayRepository tripDayRepository;
    private final ActivityRepository activityRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public TripGenerateResponse generateTrip(Long userId, GenerateTripRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));

        if (user.getAiCredits() <= 0) {
            throw AppException.badRequest("Hết lượt AI. Vui lòng mua thêm credits.");
        }

        // Validate dates
        LocalDate today = LocalDate.now();
        if (request.getStartDate().isBefore(today)) {
            throw AppException.badRequest("Ngày bắt đầu không được trước ngày hôm nay");
        }
        if (!request.getEndDate().isAfter(request.getStartDate())) {
            throw AppException.badRequest("Ngày kết thúc phải sau ngày bắt đầu");
        }

        // Trừ AI credits
        user.setAiCredits(user.getAiCredits() - 1);
        userRepository.save(user);

        // Tạo trip entity trước
        List<String> styleList = request.getStyles() != null ? request.getStyles() : List.of();
        String stylesJson;
        try {
            stylesJson = new ObjectMapper().writeValueAsString(styleList);
        } catch (JsonProcessingException e) {
            stylesJson = "[]";
        }

        Trip trip = Trip.builder()
                .user(user)
                .title(request.getDestination() + " " + ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + " ngày")
                .departure(request.getDeparture())
                .destination(request.getDestination())
                .dateStart(request.getStartDate())
                .dateEnd(request.getEndDate())
                .peopleCount(request.getPeopleCount())
                .budgetVnd(request.getBudgetVnd())
                .styles(stylesJson)
                .build();
        trip = tripRepository.save(trip);

        // Sinh lịch trình theo ngày (mock AI response cho demo)
        long numDays = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        long totalCost = 0;
        List<TripGenerateResponse.DayDetail> dayDetails = new ArrayList<>();

        String[][] foodPlaces = {
                {"Bún bò Huế", "Bánh xèo", "Bún chả Hà Nội", "Phở Hà Nội"},
                {"Cơm tấm Kiều Giang", "Bún mắm", "Lẩu cá kho", "Gỏi cuốn"},
                {"Bún đậu mắm tôm", "Cao lầu", "Mì Quảng", "Bánh canh"}
        };
        String[][] attrPlaces = {
                {"Vịnh Hạ Long", "Cầu Long Biên", "Hồ Hoàn Kiếm", "Lăng Bác"},
                {"Đường Phố Cổ Hội An", "Cầu Rồng Đà Nẵng", "Núi Ngũ Hành Sơn", "Bà Nà Hills"},
                {"Tháp Chàm Po Nagar", "Chùa Thiên Mụ", "Hòn Tằm", "Đèo Cả"}
        };

        Random random = new Random();
        long budgetPerDay = request.getBudgetVnd() / numDays;

        for (int d = 0; d < numDays; d++) {
            LocalDate dayDate = request.getStartDate().plusDays(d);

            TripDay day = TripDay.builder()
                    .trip(trip)
                    .dayNumber(d + 1)
                    .date(dayDate)
                    .build();
            day = tripDayRepository.save(day);

            List<TripGenerateResponse.ActivityDetail> activityDetails = new ArrayList<>();
            long dayCost = 0;

            // Buổi sáng: ăn sáng + tham quan
            long foodCost1 = (long) (budgetPerDay * 0.1) + random.nextInt(100_000);
            dayCost += foodCost1;
            Activity breakfast = createActivity(day, 1, java.time.LocalTime.of(7, 0),
                    foodPlaces[d % foodPlaces.length][random.nextInt(foodPlaces[d % foodPlaces.length].length)],
                    "Bữa sáng địa phương",
                    com.tranbac.chiptripbe.common.enums.ActivityType.FOOD,
                    foodCost1, 16.0544 + d * 0.01, 108.2022 + d * 0.01, d);
            activityDetails.add(toActivityDetail(breakfast));

            // Buổi sáng: tham quan
            long attrCost1 = (long) (budgetPerDay * 0.25) + random.nextInt(150_000);
            dayCost += attrCost1;
            Activity morning = createActivity(day, 2, java.time.LocalTime.of(9, 0),
                    attrPlaces[d % attrPlaces.length][random.nextInt(attrPlaces[d % attrPlaces.length].length)],
                    "Khám phá địa điểm du lịch nổi tiếng",
                    com.tranbac.chiptripbe.common.enums.ActivityType.ATTRACTION,
                    attrCost1, 16.0544 + d * 0.02, 108.2022 + d * 0.02, d);
            activityDetails.add(toActivityDetail(morning));

            // Buổi trưa: ăn trưa
            long foodCost2 = (long) (budgetPerDay * 0.15) + random.nextInt(80_000);
            dayCost += foodCost2;
            Activity lunch = createActivity(day, 3, java.time.LocalTime.of(12, 0),
                    "Nhà hàng " + request.getDestination(),
                    "Bữa trưa với đặc sản địa phương",
                    com.tranbac.chiptripbe.common.enums.ActivityType.FOOD,
                    foodCost2, 16.0544 + d * 0.01, 108.2022 + d * 0.01, d);
            activityDetails.add(toActivityDetail(lunch));

            // Buổi chiều: tham quan/thư giãn
            long attrCost2 = (long) (budgetPerDay * 0.3) + random.nextInt(200_000);
            dayCost += attrCost2;
            Activity afternoon = createActivity(day, 4, java.time.LocalTime.of(14, 30),
                    "Khu du lịch " + request.getDestination(),
                    "Trải nghiệm và thư giãn buổi chiều",
                    com.tranbac.chiptripbe.common.enums.ActivityType.ATTRACTION,
                    attrCost2, 16.0544 + d * 0.015, 108.2022 + d * 0.015, d);
            activityDetails.add(toActivityDetail(afternoon));

            // Buổi tối: ăn tối
            long foodCost3 = (long) (budgetPerDay * 0.15) + random.nextInt(100_000);
            dayCost += foodCost3;
            Activity dinner = createActivity(day, 5, java.time.LocalTime.of(18, 30),
                    "Quán ăn " + request.getDestination(),
                    "Bữa tối với hương vị địa phương",
                    com.tranbac.chiptripbe.common.enums.ActivityType.FOOD,
                    foodCost3, 16.0544 + d * 0.01, 108.2022 + d * 0.01, d);
            activityDetails.add(toActivityDetail(dinner));

            totalCost += dayCost;

            dayDetails.add(TripGenerateResponse.DayDetail.builder()
                    .id(day.getId())
                    .dayNumber(day.getDayNumber())
                    .date(day.getDate())
                    .dayCostVnd(dayCost)
                    .activities(activityDetails)
                    .build());
        }

        // Tạo checklist mặc định
        List<TripGenerateResponse.ChecklistDetail> checklistDetails = createDefaultChecklist(trip);

        log.info("Generated trip id={} for userId={} with {} days", trip.getId(), userId, numDays);

        return TripGenerateResponse.builder()
                .id(trip.getId())
                .title(trip.getTitle())
                .departure(trip.getDeparture())
                .destination(trip.getDestination())
                .dateStart(trip.getDateStart())
                .dateEnd(trip.getDateEnd())
                .peopleCount(trip.getPeopleCount())
                .budgetVnd(trip.getBudgetVnd())
                .styles(trip.getStyles())
                .createdAt(trip.getCreatedAt())
                .totalCostVnd(totalCost)
                .days(dayDetails)
                .checklist(checklistDetails)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TripSummaryResponse> getMyTrips(Long userId, Pageable pageable) {
        return tripRepository.findByUserId(userId, pageable).map(this::toSummaryResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public TripDetailResponse getTripDetail(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        return buildTripDetailResponse(trip);
    }

    @Override
    @Transactional
    public TripDetailResponse updateTrip(Long userId, Long tripId, UpdateTripRequest request) {
        Trip trip = findTripByIdAndUserId(tripId, userId);

        if (request.getTitle() != null) {
            trip.setTitle(request.getTitle());
        }
        if (request.getDateStart() != null) {
            trip.setDateStart(request.getDateStart());
        }
        if (request.getDateEnd() != null) {
            trip.setDateEnd(request.getDateEnd());
        }
        if (request.getPeopleCount() != null) {
            trip.setPeopleCount(request.getPeopleCount());
        }
        if (request.getBudgetVnd() != null) {
            trip.setBudgetVnd(request.getBudgetVnd());
        }
        if (request.getStyles() != null) {
            try {
                trip.setStyles(new ObjectMapper().writeValueAsString(request.getStyles()));
            } catch (JsonProcessingException e) {
                // ignore
            }
        }

        trip = tripRepository.save(trip);
        log.info("Updated trip id={} by userId={}", tripId, userId);
        return buildTripDetailResponse(trip);
    }

    @Override
    @Transactional
    public void deleteTrip(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        tripRepository.delete(trip);
        log.info("Deleted trip id={} by userId={}", tripId, userId);
    }

    @Override
    @Transactional
    public TripDetailResponse cloneTrip(Long userId, Long tripId) {
        Trip original = findTripByIdAndUserId(tripId, userId);

        Trip clone = Trip.builder()
                .user(original.getUser())
                .title(original.getTitle() + " (Copy)")
                .departure(original.getDeparture())
                .destination(original.getDestination())
                .dateStart(original.getDateStart())
                .dateEnd(original.getDateEnd())
                .peopleCount(original.getPeopleCount())
                .budgetVnd(original.getBudgetVnd())
                .styles(original.getStyles())
                .build();
        clone = tripRepository.save(clone);

        // Clone days and activities
        for (TripDay originalDay : original.getDays()) {
            TripDay cloneDay = TripDay.builder()
                    .trip(clone)
                    .dayNumber(originalDay.getDayNumber())
                    .date(originalDay.getDate())
                    .build();
            cloneDay = tripDayRepository.save(cloneDay);

            for (Activity originalActivity : originalDay.getActivities()) {
                Activity cloneActivity = Activity.builder()
                        .day(cloneDay)
                        .startTime(originalActivity.getStartTime())
                        .name(originalActivity.getName())
                        .description(originalActivity.getDescription())
                        .type(originalActivity.getType())
                        .costVnd(originalActivity.getCostVnd())
                        .latitude(originalActivity.getLatitude())
                        .longitude(originalActivity.getLongitude())
                        .imageUrl(originalActivity.getImageUrl())
                        .bookingUrl(originalActivity.getBookingUrl())
                        .displayOrder(originalActivity.getDisplayOrder())
                        .build();
                activityRepository.save(cloneActivity);
            }
        }

        // Clone checklist
        for (ChecklistItem originalItem : original.getChecklist()) {
            ChecklistItem cloneItem = ChecklistItem.builder()
                    .trip(clone)
                    .category(originalItem.getCategory())
                    .name(originalItem.getName())
                    .isChecked(false)
                    .displayOrder(originalItem.getDisplayOrder())
                    .build();
            checklistItemRepository.save(cloneItem);
        }

        log.info("Cloned trip id={} to new trip id={} by userId={}", tripId, clone.getId(), userId);
        return buildTripDetailResponse(clone);
    }

    @Override
    @Transactional
    public ShareTokenResponse enableShare(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        String token = UUID.randomUUID().toString().replace("-", "");
        trip.setShareToken(token);
        tripRepository.save(trip);
        log.info("Enabled share for trip id={}, token={}", tripId, token);
        return ShareTokenResponse.builder().shareToken(token).build();
    }

    @Override
    @Transactional
    public void disableShare(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        trip.setShareToken(null);
        tripRepository.save(trip);
        log.info("Disabled share for trip id={}", tripId);
    }

    @Override
    @Transactional(readOnly = true)
    public TripDetailResponse getSharedTrip(String shareToken) {
        Trip trip = tripRepository.findByShareToken(shareToken)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi được chia sẻ"));
        return buildTripDetailResponse(trip);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private Trip findTripByIdAndUserId(Long tripId, Long userId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        if (!trip.getUser().getId().equals(userId)) {
            throw AppException.forbidden("Bạn không có quyền với chuyến đi này");
        }
        return trip;
    }

    private TripDetailResponse buildTripDetailResponse(Trip trip) {
        List<TripDay> days = tripDayRepository.findByTripIdOrderByDayNumber(trip.getId());
        List<ChecklistItem> checklist = checklistItemRepository.findByTripIdOrderByDisplayOrder(trip.getId());

        List<TripDetailResponse.DayDetail> dayDetails = days.stream().map(day -> {
            List<Activity> activities = activityRepository.findByDayIdOrderByDisplayOrder(day.getId());
            List<TripDetailResponse.ActivityDetail> activityDetails = activities.stream()
                    .map(this::toTripDetailActivityDetail).toList();
            return TripDetailResponse.DayDetail.builder()
                    .id(day.getId())
                    .dayNumber(day.getDayNumber())
                    .date(day.getDate())
                    .dayCostVnd(activityDetails.stream().mapToLong(a -> a.getCostVnd() != null ? a.getCostVnd() : 0L).sum())
                    .activities(activityDetails)
                    .build();
        }).toList();

        List<TripDetailResponse.ChecklistItemDetail> checklistDetails = checklist.stream()
                .map(c -> TripDetailResponse.ChecklistItemDetail.builder()
                        .id(c.getId())
                        .category(c.getCategory() != null ? c.getCategory().name() : null)
                        .name(c.getName())
                        .isChecked(c.getIsChecked())
                        .displayOrder(c.getDisplayOrder())
                        .build()).toList();

        return TripDetailResponse.builder()
                .id(trip.getId())
                .title(trip.getTitle())
                .departure(trip.getDeparture())
                .destination(trip.getDestination())
                .dateStart(trip.getDateStart())
                .dateEnd(trip.getDateEnd())
                .peopleCount(trip.getPeopleCount())
                .budgetVnd(trip.getBudgetVnd())
                .styles(trip.getStyles())
                .createdAt(trip.getCreatedAt())
                .updatedAt(trip.getUpdatedAt())
                .totalCostVnd(dayDetails.stream().mapToLong(d -> d.getDayCostVnd() != null ? d.getDayCostVnd() : 0L).sum())
                .shareToken(trip.getShareToken())
                .user(TripDetailResponse.UserInfo.builder()
                        .id(trip.getUser().getId())
                        .email(trip.getUser().getEmail())
                        .fullName(trip.getUser().getFullName())
                        .build())
                .days(dayDetails)
                .checklist(checklistDetails)
                .build();
    }

    private TripSummaryResponse toSummaryResponse(Trip trip) {
        return TripSummaryResponse.builder()
                .id(trip.getId())
                .userId(trip.getUser().getId())
                .userFullName(trip.getUser().getFullName())
                .userEmail(trip.getUser().getEmail())
                .title(trip.getTitle())
                .departure(trip.getDeparture())
                .destination(trip.getDestination())
                .dateStart(trip.getDateStart())
                .dateEnd(trip.getDateEnd())
                .peopleCount(trip.getPeopleCount())
                .budgetVnd(trip.getBudgetVnd())
                .styles(trip.getStyles())
                .createdAt(trip.getCreatedAt())
                .updatedAt(trip.getUpdatedAt())
                .build();
    }

    private Activity createActivity(TripDay day, int order, java.time.LocalTime time,
                                   String name, String desc,
                                   com.tranbac.chiptripbe.common.enums.ActivityType type,
                                   long cost, double lat, double lng, int dayIndex) {
        Activity activity = Activity.builder()
                .day(day)
                .displayOrder(order)
                .startTime(time)
                .name(name)
                .description(desc)
                .type(type)
                .costVnd(cost)
                .latitude(java.math.BigDecimal.valueOf(lat + dayIndex * 0.005))
                .longitude(java.math.BigDecimal.valueOf(lng + dayIndex * 0.005))
                .build();
        return activityRepository.save(activity);
    }

    private TripGenerateResponse.ActivityDetail toActivityDetail(Activity activity) {
        return TripGenerateResponse.ActivityDetail.builder()
                .id(activity.getId())
                .startTime(activity.getStartTime())
                .name(activity.getName())
                .description(activity.getDescription())
                .type(activity.getType())
                .costVnd(activity.getCostVnd())
                .latitude(activity.getLatitude())
                .longitude(activity.getLongitude())
                .imageUrl(activity.getImageUrl())
                .bookingUrl(activity.getBookingUrl())
                .displayOrder(activity.getDisplayOrder())
                .build();
    }

    private TripDetailResponse.ActivityDetail toTripDetailActivityDetail(Activity activity) {
        return TripDetailResponse.ActivityDetail.builder()
                .id(activity.getId())
                .startTime(activity.getStartTime())
                .name(activity.getName())
                .description(activity.getDescription())
                .type(activity.getType())
                .costVnd(activity.getCostVnd())
                .latitude(activity.getLatitude())
                .longitude(activity.getLongitude())
                .imageUrl(activity.getImageUrl())
                .bookingUrl(activity.getBookingUrl())
                .displayOrder(activity.getDisplayOrder())
                .build();
    }

    private List<TripGenerateResponse.ChecklistDetail> createDefaultChecklist(Trip trip) {
        List<TripGenerateResponse.ChecklistDetail> items = new ArrayList<>();
        String[][] defaults = {
                {"CMND/CCCD", "PAPERS"},
                {"Vé máy bay / giấy tờ đặt tour", "PAPERS"},
                {"Tiền mặt / Thẻ ATM", "PAPERS"},
                {"Quần áo theo thời tiết", "CLOTHES"},
                {"Kem chống nắng", "HYGIENE"},
                {"Thuốc men / Bảo hiểm du lịch", "OTHER"},
        };

        for (int i = 0; i < defaults.length; i++) {
            ChecklistItem item = ChecklistItem.builder()
                    .trip(trip)
                    .category(com.tranbac.chiptripbe.common.enums.ChecklistCategory.valueOf(defaults[i][1]))
                    .name(defaults[i][0])
                    .isChecked(false)
                    .displayOrder(i)
                    .build();
            checklistItemRepository.save(item);
            items.add(TripGenerateResponse.ChecklistDetail.builder()
                    .id(item.getId())
                    .category(item.getCategory() != null ? item.getCategory().name() : defaults[i][1])
                    .name(item.getName())
                    .isChecked(false)
                    .displayOrder(i)
                    .build());
        }
        return items;
    }
}
