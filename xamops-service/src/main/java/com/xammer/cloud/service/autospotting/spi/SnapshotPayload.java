package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.domain.autospotting.Provider;

import java.util.List;

/**
 * A snapshot captured for restore (design-core.md §9.1, §2.4 {@code spot_group_snapshot}).
 * {@code takeSnapshot} returns one from a {@link ReadOnlyCloudView}: it captures enough to restore
 * exactly, or sets {@code exact=false} with reasons (FR-SPN-12). The program's first mutating step
 * persists it before any change.
 *
 * <p>Cloud-neutral: {@code payloadJson} is the provider-specific original configuration and
 * {@code payloadSha256} its hash; {@code schemaVersion} versions the payload shape.
 */
public record SnapshotPayload(
        Provider provider,
        int schemaVersion,
        boolean exact,
        List<String> inexactReasons,
        String payloadJson,
        String payloadSha256) {

    public SnapshotPayload {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        inexactReasons = inexactReasons == null ? List.of() : List.copyOf(inexactReasons);
    }

    public static SnapshotPayload exact(Provider provider, int schemaVersion, String payloadJson,
                                        String payloadSha256) {
        return new SnapshotPayload(provider, schemaVersion, true, List.of(), payloadJson, payloadSha256);
    }
}
