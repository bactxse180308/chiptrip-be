package com.tranbac.chiptripbe.module.ai.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DestinationSuggestion {
    private String name;   // "Đà Lạt"
    private String emoji;  // "🌸"
    private String desc;   // Mô tả tiếng Việt < 30 từ
}
