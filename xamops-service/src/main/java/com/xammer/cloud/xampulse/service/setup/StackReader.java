package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.domain.CloudAccount;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only view of a customer's CloudFormation stack, read under the XamOps connection role. */
public interface StackReader {

    record Stack(String status, String reason, String stackId, Map<String, String> outputs, List<String> events) {}

    /** Empty when the stack does not exist. Throws XamPulseException (XP_AWS_UNAVAILABLE) when AWS cannot be asked. */
    Optional<Stack> describe(CloudAccount account, String region, String stackName);

    /** Row state used in xp_account_region and the setup overview: NONE, DEPLOYING, OK or FAILED. */
    static String rowState(String cfnStatus) {
        if (cfnStatus == null || cfnStatus.equals("DELETE_COMPLETE")) {
            return "NONE";
        }
        if (cfnStatus.equals("CREATE_COMPLETE") || cfnStatus.equals("UPDATE_COMPLETE")
                || cfnStatus.equals("UPDATE_ROLLBACK_COMPLETE") || cfnStatus.equals("IMPORT_COMPLETE")) {
            return "OK";
        }
        if (cfnStatus.endsWith("_IN_PROGRESS") && !cfnStatus.startsWith("ROLLBACK") && !cfnStatus.startsWith("DELETE")
                && !cfnStatus.startsWith("UPDATE_ROLLBACK")) {
            return "DEPLOYING";
        }
        return "FAILED";
    }
}
