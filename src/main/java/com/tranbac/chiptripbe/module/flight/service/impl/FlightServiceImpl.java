package com.tranbac.chiptripbe.module.flight.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.common.util.VietnamAirports;
import com.tranbac.chiptripbe.module.flight.dto.FlightSuggestionResponse;
import com.tranbac.chiptripbe.module.flight.dto.FlightSuggestionResponse.BookingOption;
import com.tranbac.chiptripbe.module.flight.dto.FlightSuggestionResponse.FlightLeg;
import com.tranbac.chiptripbe.module.flight.entity.FlightCache;
import com.tranbac.chiptripbe.module.flight.repository.FlightCacheRepository;
import com.tranbac.chiptripbe.module.flight.service.FlightService;
import com.tranbac.chiptripbe.module.geocoding.client.SerpApiClient;
import com.tranbac.chiptripbe.module.geocoding.client.SerpApiClient.FlightOption;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.user.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
class FlightServiceImpl implements FlightService {

    private static final long CACHE_TTL_HOURS = 6;

    private final TripRepository tripRepository;
    private final SerpApiClient serpApiClient;
    private final FlightCacheRepository flightCacheRepository;
    private final ObjectMapper objectMapper;
    private final EntitlementService entitlementService;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FlightSuggestionResponse getFlightSuggestion(Long userId, Long tripId) {
        Trip trip = tripRepository.findByIdAndUserId(tripId, userId)
                .orElseThrow(() -> AppException.forbidden("Bạn không có quyền với chuyến đi này"));

        // Gợi ý vé máy bay là tính năng Premium — gate "createdAsPremium HOẶC premium hiện tại":
        // snapshot tránh cliff; isPremium hiện tại cho phép dùng trên chuyến tạo lúc còn Normal sau khi nạp gói.
        // Không tốn credit → mở khoá không trừ gì.
        if (!trip.isGeneratedAsPremium() && !entitlementService.isPremium(userId)) {
            throw AppException.premiumRequired();
        }

        String depId = VietnamAirports.resolve(trip.getDeparture())
                .orElseThrow(() -> AppException.badRequest("Chưa hỗ trợ sân bay cho điểm đi: " + trip.getDeparture()));
        String arrId = VietnamAirports.resolve(trip.getDestination())
                .orElseThrow(() -> AppException.badRequest("Chưa hỗ trợ sân bay cho điểm đến: " + trip.getDestination()));
        if (depId.equals(arrId)) {
            throw AppException.badRequest("Điểm đi và điểm đến dùng chung sân bay, không cần đặt vé máy bay.");
        }

        LocalDate out = trip.getDateStart();
        LocalDate ret = (trip.getDateEnd() != null && trip.getDateEnd().isAfter(out)) ? trip.getDateEnd() : null;
        Integer adults = trip.getPeopleCount() != null ? trip.getPeopleCount() : 1;

        String routeKey = depId + "_" + arrId + "_" + out + "_" + (ret == null ? "OW" : ret) + "_" + adults;

        Optional<FlightCache> cached = flightCacheRepository.findByRouteKey(routeKey);
        if (cached.isPresent() && isFresh(cached.get())) {
            FlightSuggestionResponse r = deserialize(cached.get().getPayloadJson());
            if (r != null) return r;
        }

        FlightSuggestionResponse response = buildSuggestion(depId, arrId, out, ret, adults);

        // Chỉ cache khi có kết quả thật — tránh giấu vé 6h nếu SerpApi lỗi tạm thời.
        if (response.outbound() != null) {
            try {
                FlightCache row = cached.orElseGet(FlightCache::new);
                row.setRouteKey(routeKey);
                row.setPayloadJson(serialize(response));
                flightCacheRepository.save(row);
            } catch (DataIntegrityViolationException ignored) {
                // concurrent writer đã insert cùng routeKey — bỏ qua
            } catch (Exception e) {
                log.warn("Failed to cache flight suggestion for {}: {}", routeKey, e.getMessage());
            }
        }

        return response;
    }

    /** 3-call flow: outbound → (return via departure_token) → booking options via booking_token. */
    private FlightSuggestionResponse buildSuggestion(String depId, String arrId, LocalDate out, LocalDate ret, Integer adults) {
        boolean roundTrip = ret != null;
        String googleUrl = googleFlightsUrl(depId, arrId, out, ret);

        List<FlightOption> outboundOpts = serpApiClient.searchFlights(depId, arrId, out, ret, adults, null);
        if (outboundOpts.isEmpty()) {
            return new FlightSuggestionResponse(depId, arrId, out, ret, adults, null, null, null, List.of(), googleUrl);
        }
        FlightOption outboundOpt = outboundOpts.get(0);

        FlightOption returnOpt = null;
        Long totalPrice = outboundOpt.priceVnd();
        String bookingToken = outboundOpt.bookingToken();

        if (roundTrip && outboundOpt.departureToken() != null && !outboundOpt.departureToken().isBlank()) {
            List<FlightOption> returnOpts = serpApiClient.searchFlights(depId, arrId, out, ret, adults, outboundOpt.departureToken());
            if (!returnOpts.isEmpty()) {
                returnOpt = returnOpts.get(0);
                if (returnOpt.priceVnd() != null) totalPrice = returnOpt.priceVnd();
                bookingToken = returnOpt.bookingToken();
            }
        }

        List<BookingOption> bookingOptions = List.of();
        if (bookingToken != null && !bookingToken.isBlank()) {
            bookingOptions = serpApiClient.fetchFlightBookingOptions(depId, arrId, out, ret, adults, bookingToken)
                    .stream()
                    .map(b -> new BookingOption(b.source(), b.priceVnd(), b.bookingLink()))
                    .toList();
        }

        return new FlightSuggestionResponse(
                depId, arrId, out, ret, adults, totalPrice,
                toLeg(outboundOpt), toLeg(returnOpt), bookingOptions, googleUrl);
    }

    private FlightLeg toLeg(FlightOption opt) {
        if (opt == null || opt.segments() == null || opt.segments().isEmpty()) return null;
        var first = opt.segments().get(0);
        var last = opt.segments().get(opt.segments().size() - 1);
        return new FlightLeg(
                first.airline(), first.airlineLogo(),
                first.departureAirport(), first.departureTime(),
                last.arrivalAirport(), last.arrivalTime(),
                opt.totalDurationMinutes(), opt.stops());
    }

    private String googleFlightsUrl(String depId, String arrId, LocalDate out, LocalDate ret) {
        String q = "Flights from " + depId + " to " + arrId + " on " + out
                + (ret != null ? " returning " + ret : "");
        return "https://www.google.com/travel/flights?hl=vi&gl=vn&q="
                + URLEncoder.encode(q, StandardCharsets.UTF_8);
    }

    private boolean isFresh(FlightCache cache) {
        return cache.getUpdatedAt() != null
                && cache.getUpdatedAt().isAfter(LocalDateTime.now().minusHours(CACHE_TTL_HOURS));
    }

    private String serialize(FlightSuggestionResponse r) {
        try {
            return objectMapper.writeValueAsString(r);
        } catch (Exception e) {
            return null;
        }
    }

    private FlightSuggestionResponse deserialize(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, FlightSuggestionResponse.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize cached flight payload: {}", e.getMessage());
            return null;
        }
    }
}
