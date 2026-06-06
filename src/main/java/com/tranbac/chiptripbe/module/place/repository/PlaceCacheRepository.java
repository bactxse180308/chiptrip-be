package com.tranbac.chiptripbe.module.place.repository;

import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlaceCacheRepository extends JpaRepository<PlaceCache, Long> {

    /**
     * Lookup theo cặp (normalizedName, normalizedDestination) — tránh nhầm cùng
     * tên giữa các tỉnh/thành. Trả first row để an toàn với dữ liệu cũ có thể trùng.
     */
    Optional<PlaceCache> findFirstByNormalizedNameAndNormalizedDestination(
            String normalizedName, String normalizedDestination);

    /** Fallback cho dữ liệu cũ chưa có normalizedDestination. */
    Optional<PlaceCache> findFirstByNormalizedNameAndNormalizedDestinationIsNull(String normalizedName);

    /**
     * An toàn với duplicate goong_place_id trong DB (tránh NonUniqueResultException).
     * Lấy row mới nhất theo id.
     */
    Optional<PlaceCache> findFirstByGoongPlaceIdOrderByIdDesc(String goongPlaceId);

    /**
     * Trả về cache row "tốt nhất" cho 1 goongPlaceId, theo thứ tự ưu tiên:
     * 1) đã enrich SerpApi (serpEnriched = true)
     * 2) có rating
     * 3) có reviews / photos / opening hours / address / toạ độ
     * 4) updatedAt mới hơn
     * 5) id lớn hơn (tie-break cuối cùng)
     * Dùng cho dedup khi vẫn còn nhiều row cũ cùng goongPlaceId.
     */
    @Query("""
        SELECT p FROM PlaceCache p
        WHERE p.goongPlaceId = :goongPlaceId
        ORDER BY
            CASE WHEN p.serpEnriched = TRUE THEN 0 ELSE 1 END,
            CASE WHEN p.rating IS NOT NULL THEN 0 ELSE 1 END,
            CASE WHEN p.reviewsJson IS NOT NULL THEN 0 ELSE 1 END,
            CASE WHEN p.photosJson IS NOT NULL THEN 0 ELSE 1 END,
            CASE WHEN p.openingHoursJson IS NOT NULL THEN 0 ELSE 1 END,
            CASE WHEN p.address IS NOT NULL THEN 0 ELSE 1 END,
            CASE WHEN p.latitude IS NOT NULL AND p.longitude IS NOT NULL THEN 0 ELSE 1 END,
            p.updatedAt DESC,
            p.id DESC
        """)
    List<PlaceCache> findRankedByGoongPlaceId(@Param("goongPlaceId") String goongPlaceId, Pageable pageable);

    default Optional<PlaceCache> findBestByGoongPlaceId(String goongPlaceId) {
        if (goongPlaceId == null || goongPlaceId.isBlank()) return Optional.empty();
        return findRankedByGoongPlaceId(goongPlaceId, PageRequest.of(0, 1)).stream().findFirst();
    }
}
