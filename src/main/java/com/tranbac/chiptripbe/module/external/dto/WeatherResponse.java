package com.tranbac.chiptripbe.module.external.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class WeatherResponse {

    private String city;
    private List<DayForecast> forecasts;

    @Getter
    @Builder
    public static class DayForecast {
        private LocalDate date;
        private String condition;
        private String icon;
        private Double tempMin;
        private Double tempMax;
        private Double humidity;
        private Double windSpeed;
        private String description;
    }
}
