package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.trip.dto.request.CreateActivityRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.ReorderActivitiesRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateActivityRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;

public interface ActivityService {

    TripDetailResponse.ActivityDetail addActivity(Long userId, Long tripId, Long dayId, CreateActivityRequest request);

    TripDetailResponse.ActivityDetail updateActivity(Long userId, Long tripId, Long dayId, Long activityId, UpdateActivityRequest request);

    void deleteActivity(Long userId, Long tripId, Long dayId, Long activityId);

    void reorderActivities(Long userId, Long tripId, Long dayId, ReorderActivitiesRequest request);
}
