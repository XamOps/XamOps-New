package com.xammer.cloud.service.security;

import com.xammer.cloud.domain.AwsInventorySyncState;
import com.xammer.cloud.repository.AwsInventorySyncStateRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Answers "which security detectors are actually running for this account".
 *
 * <p>
 * This exists to close a specific and dangerous ambiguity. An account whose onboarding stack
 * predates the exposure rule produces no exposure findings — and so does a genuinely well-run
 * account. Both render as a clean Security Center page. Without something to distinguish them, the
 * product tells an unmonitored customer they are secure, which is worse than telling them nothing.
 *
 * <p>
 * The signal is the envelope version the customer's forwarder stamps on every message, recorded on
 * the sync state. It is a capability check rather than a version-string comparison: we report what
 * we have observed arriving, not what we believe is deployed.
 */
@Service
public class DetectorCoverageService {

    /**
     * Envelope version that carries slimmed CloudTrail detail.
     *
     * <p>
     * Below this, the exposure detectors cannot run at all: they decide severity from the request
     * parameters, and without them every security group change looks identical.
     */
    public static final int SCHEMA_WITH_EXPOSURE_DETAIL = 2;

    private final AwsInventorySyncStateRepository syncStateRepository;

    public DetectorCoverageService(AwsInventorySyncStateRepository syncStateRepository) {
        this.syncStateRepository = syncStateRepository;
    }

    /** What the UI needs to decide whether to show the update-your-stack nudge. */
    public Map<String, Object> coverageFor(Long cloudAccountId) {
        Map<String, Object> body = new LinkedHashMap<>();

        Optional<AwsInventorySyncState> stateOpt = cloudAccountId == null
                ? Optional.empty()
                : syncStateRepository.findById(cloudAccountId);

        Integer version = stateOpt.map(AwsInventorySyncState::getEventSchemaVersion).orElse(null);
        boolean everReceived = stateOpt.map(s -> s.getLastEventAt() != null).orElse(false);

        body.put("eventsReceived", everReceived);
        body.put("forwarderVersion", version);
        body.put("expectedVersion", SCHEMA_WITH_EXPOSURE_DETAIL);

        // Three genuinely different states, deliberately not collapsed into a boolean:
        //
        //   NOT_CONNECTED  — no event has ever arrived. The customer may not have deployed the
        //                    optional stack at all, which is our problem to explain, not to fix.
        //   STACK_OUTDATED — events arrive but without detail. This is the one worth nudging.
        //   CURRENT        — everything available is running.
        //
        // Collapsing the first two would send an "update your stack" prompt to someone who never
        // installed it, and that message tells them to do the wrong thing.
        String status;
        if (!everReceived) {
            status = "NOT_CONNECTED";
        } else if (version == null || version < SCHEMA_WITH_EXPOSURE_DETAIL) {
            status = "STACK_OUTDATED";
        } else {
            status = "CURRENT";
        }
        body.put("status", status);

        List<String> missing = "CURRENT".equals(status)
                ? List.of()
                : new ArrayList<>(ExposureDetector.handledEventNames());
        body.put("missingDetectorCount", missing.size());
        body.put("missingDetectors", missing);

        body.put("activeDetectorCount", "CURRENT".equals(status)
                ? ExposureDetector.handledEventNames().size()
                        + SecurityFindingDetector.detectedEventNames().size()
                : SecurityFindingDetector.detectedEventNames().size());

        body.put("message", messageFor(status, missing.size()));
        return body;
    }

    /**
     * Wording aimed at what the reader should do next.
     *
     * <p>
     * The outdated case names the consequence rather than the version number, because "you are on
     * schema 1" tells a customer nothing about their risk while "resources being made public are
     * not being detected" tells them exactly what they are missing.
     */
    private String messageFor(String status, int missingCount) {
        switch (status) {
            case "NOT_CONNECTED":
                return "No security events have reached XamOps for this account. Findings are "
                        + "coming from scheduled scans only, so a change made now would not be "
                        + "noticed until the next scan.";
            case "STACK_OUTDATED":
                return "Your account is missing " + missingCount + " security detectors. Resources "
                        + "being made public — an open security group, a public bucket policy, a "
                        + "database given a public endpoint — are not being detected. Update your "
                        + "XamOps CloudFormation stack to enable them.";
            default:
                return "All available security detectors are running for this account.";
        }
    }

    /** Convenience for callers that only need the boolean. */
    public boolean isCurrent(Long cloudAccountId) {
        Map<String, Object> coverage = coverageFor(cloudAccountId);
        return "CURRENT".equals(coverage.get("status"));
    }

    /** Detector inventory, independent of any account. Useful for docs and support. */
    public Map<String, Object> catalogue() {
        Map<String, Object> body = new HashMap<>();
        body.put("nameOnlyDetectors", SecurityFindingDetector.detectedEventNames());
        body.put("detailAwareDetectors", ExposureDetector.handledEventNames());
        return body;
    }
}
