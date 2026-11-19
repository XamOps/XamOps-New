package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xammer.cloud.service.matters.ExposureCategory;

import java.util.Arrays;
import java.util.List;

/**
 * Step 0 of the Data Lineage page: the exposure category cards (build 4 design 5.4, endpoint L1).
 *
 * @param available  false while the exposure switch is off: the page shows the cards greyed.
 * @param categories in {@link ExposureCategory} order.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LineageCategories(boolean available, List<Category> categories) {

    /**
     * @param id        the {@link ExposureCategory} name: the URL value and the card icon.
     * @param name      the card title.
     * @param exposures the card's count; null while unavailable.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Category(String id, String name, Long exposures) {
    }

    /** The six categories with their fixed titles and no counts. */
    public static LineageCategories unavailable() {
        return new LineageCategories(false, Arrays.stream(ExposureCategory.values())
                .map(category -> new Category(category.name(), category.title(), null))
                .toList());
    }
}
