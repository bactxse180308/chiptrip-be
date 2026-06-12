package com.tranbac.chiptripbe.module.flight.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Gợi ý chuyến bay cho 1 trip (điểm đi → điểm đến, ngày, số khách).
 * outbound luôn có nếu tìm được vé; returnLeg null nếu one-way.
 * bookingOptions từ Google Flights Booking Options (book_with + giá + link).
 */
public record FlightSuggestionResponse(
        String departureId,
        String arrivalId,
        LocalDate outboundDate,
        LocalDate returnDate,
        Integer adults,
        Long totalPriceVnd,
        FlightLeg outbound,
        FlightLeg returnLeg,
        List<BookingOption> bookingOptions,
        String googleFlightsUrl
) {
    public record FlightLeg(
            String airline,
            String airlineLogo,
            String departureAirport,
            String departureTime,
            String arrivalAirport,
            String arrivalTime,
            Integer durationMinutes,
            Integer stops
    ) {}

    public record BookingOption(String source, Long priceVnd, String bookingLink) {}
}
