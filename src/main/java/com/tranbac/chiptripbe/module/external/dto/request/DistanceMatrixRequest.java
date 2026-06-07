package com.tranbac.chiptripbe.module.external.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DistanceMatrixRequest {

    /** Danh sách tọa độ liên tiếp [A, B, C, D...]. BE tính từng cặp consecutive (A→B, B→C, C→D). */
    @NotEmpty
    private List<Point> points;

    /** "car" | "bike" | "taxi" | "hd" — default car. */
    private String vehicle;

    @Getter
    @Setter
    public static class Point {
        private double lat;
        private double lng;
    }
}
