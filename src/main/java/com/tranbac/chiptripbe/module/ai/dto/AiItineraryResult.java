package com.tranbac.chiptripbe.module.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/** Internal DTO dùng để parse JSON từ LLM. Không expose ra ngoài API. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiItineraryResult {

    private String title;
    private List<AiDay> days;
    private List<AiChecklistItem> checklist;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AiDay {
        private Integer dayNumber;
        private String date;
        private List<AiActivity> activities;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AiActivity {
        private String time;
        private String name;
        private String description;
        private String type;
        private Long costVnd;
        /** Chuỗi tìm kiếm để backend geocode. Ví dụ: "Bánh căn Nhà Chung Đà Lạt". */
        private String searchQuery;
        private String bookingUrl;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AiChecklistItem {
        private String category;
        private String name;
    }
}