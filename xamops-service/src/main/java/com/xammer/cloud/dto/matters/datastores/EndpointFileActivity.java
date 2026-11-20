package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The Endpoint file drawer's File Activity: the file's MIP tag history, in API order (F6; plan C18). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointFileActivity(List<Entry> entries) {

    /**
     * @param action          upper-cased: TAG_APPLIED, TAG_CHANGED, TAG_REMOVED, TAG_SKIPPED, USER_OVERRIDE…
     * @param actionLabel     "Tag applied"; any other code as it is.
     * @param occurredAt      epoch seconds.
     * @param keywordsWritten true only when the vendor says boolean true; so is mipLabelWritten.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Entry(String action, String actionLabel, String selectedTag, String previousTag,
                        String performedBy, Long occurredAt, boolean keywordsWritten, boolean mipLabelWritten) {
    }
}
