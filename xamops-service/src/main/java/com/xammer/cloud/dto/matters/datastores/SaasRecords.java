package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A SaaS datastore's records by data element or by document class (design 5.4 item 2). Also the
 * {@code dssaasrec} snapshot behind the file list's membership check.
 *
 * @param view echoes the request: data_elements or document_classes.
 * @param rows in API order.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasRecords(String view, List<Row> rows) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Row(String name, String categoryLabel, long count) {
    }
}
