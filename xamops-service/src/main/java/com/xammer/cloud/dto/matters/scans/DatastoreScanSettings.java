package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A datastore's Scan Settings (design 5.4), whitelisted field by field. No credential, URI, user name or
 * assist id is ever a component.
 *
 * @param exportAssistName    the name of the export assist, resolved by the service
 * @param discoveryAssistName the name of the discovery assist, resolved by the service
 * @param credentialsSaved    the vendor reports saved credentials ({@code isSaved}); the values are never read
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreScanSettings(Parameters parameters, List<String> inclusionPaths, List<String> exclusionPaths,
                                    boolean allDatabases, List<String> selectedDatabases, Boolean accessible,
                                    String exportAssistName, String discoveryAssistName, String host, Integer port,
                                    String serviceName, boolean credentialsSaved) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Parameters(Integer numFoldersLeafNode, Integer parallelism, Double timeOutHours,
                             Integer rowSamplingLimit, Integer samplingPercentageLimit) {
    }

    /** A copy with the assist names the service resolved. */
    public DatastoreScanSettings withAssistNames(String exportName, String discoveryName) {
        return new DatastoreScanSettings(parameters, inclusionPaths, exclusionPaths, allDatabases, selectedDatabases,
                accessible, exportName, discoveryName, host, port, serviceName, credentialsSaved);
    }
}
