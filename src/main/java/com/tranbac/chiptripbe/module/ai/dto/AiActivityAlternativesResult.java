package com.tranbac.chiptripbe.module.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiActivityAlternativesResult {
    private List<Option> alternatives;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Option {
        private String name;
        private String description;
        private String type;
        private Long costVnd;
        private String searchQuery;
        private String reason;
    }
}
