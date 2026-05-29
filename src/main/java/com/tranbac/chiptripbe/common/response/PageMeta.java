package com.tranbac.chiptripbe.common.response;

import org.springframework.data.domain.Page;

public record PageMeta(int page, int size, long totalElements, int totalPages, boolean last) {

    public static PageMeta of(Page<?> page) {
        return new PageMeta(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }
}