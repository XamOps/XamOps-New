package com.xammer.cloud.dto.matters.pages;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The Document Classes page (design 5.4): one row per class, with its category.
 *
 * @param categories the category names in route order, de-duplicated
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DocumentClassList(List<String> categories, List<Row> rows) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Row(String name, String category) {
    }
}
