package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The SaaS file drawer's File Copies and Related Files tabs: the copies other than the opened file
 * (design 5.4 item 17).
 *
 * @param available false when the copies route answered not found.
 * @param copies    in API order.
 * @param locations distinct lanes among the copies: the Locations card.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasFileCopies(boolean available, List<Copy> copies, int locations) {

    /**
     * @param key           an opaque React key.
     * @param extension     the file icon and the label under the name.
     * @param path          File Path; null on SaaS copies.
     * @param provider      with driveType and os, the source icon: the first non-null of the three.
     * @param sourceLabel   Related Files' Source: "Google Drive", "Gmail", "Endpoint".
     * @param locationLabel the Datastore column's second line: "SaaS · Gmail".
     * @param ownerEmail    Where, User/Device Name and the "Same person" rule.
     * @param userOrDevice  User/Device Name.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Copy(String key, String fileName, String extension, String path, String driveType, String os,
                       String provider, String sourceLabel, String locationLabel, String ownerName,
                       String ownerEmail, String userOrDevice) {
    }

    /** The copies route answered not found. */
    public static SaasFileCopies unavailable() {
        return new SaasFileCopies(false, List.of(), 0);
    }
}
