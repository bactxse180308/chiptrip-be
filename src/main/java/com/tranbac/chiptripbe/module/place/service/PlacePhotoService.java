package com.tranbac.chiptripbe.module.place.service;

import java.util.Optional;

public interface PlacePhotoService {

    /**
     * Loai URL hong neu no thuc su thuoc cache hien tai, tang failure counter va danh dau STALE.
     * Tra false neu URL da bi loai hoac khong thuoc dia diem.
     */
    boolean reportFailure(Long placeId, String failedUrl);

    /** Claim atomic de chi mot worker duoc refresh cung mot dia diem. */
    boolean tryStartRefresh(Long placeId);

    Optional<PhotoRefreshSource> getRefreshSource(Long placeId);

    void completeRefresh(Long placeId, String photosJson);

    void failRefresh(Long placeId);

    record PhotoRefreshSource(Long placeId, String serpDataId) {
    }
}
