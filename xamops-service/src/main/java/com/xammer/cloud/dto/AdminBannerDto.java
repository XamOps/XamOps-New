package com.xammer.cloud.dto;

import java.time.Instant;
import java.util.List;

/** Request and response shapes for admin announcement banners. */
public final class AdminBannerDto {

    private AdminBannerDto() {
    }

    /** Body of a create request. {@code clientIds} is ignored when {@code targetAll} is true. */
    public record CreateRequest(String heading, String body, Boolean targetAll, List<Long> clientIds) {
    }

    public record ClientOption(Long id, String name) {
    }

    /** A banner as the admin dashboard lists it. */
    public record AdminView(Long id, String heading, String body, boolean targetAll,
            List<ClientOption> targetClients, String createdBy, Instant createdAt) {
    }

    /** A banner as a XamOps client sees it. Leaves out who it targets and who wrote it. */
    public record ClientView(Long id, String heading, String body, Instant createdAt) {
    }
}
