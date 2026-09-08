package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a SQL query against a CUR's Athena table and returns the rows.
 *
 * <p>Knows nothing about cost semantics — it resolves which credentials to use,
 * submits, polls, pages, and hands back strings. Query construction lives in
 * {@link CurCostDataSource} so the SQL and the columns it depends on stay next to
 * the code that interprets them.
 */
@Service
public class CurAthenaQueryService {

    private static final Logger logger = LoggerFactory.getLogger(CurAthenaQueryService.class);

    /**
     * Athena's first poll almost never succeeds, and a tight loop just burns
     * GetQueryExecution calls against a per-account rate limit shared with
     * everything else in the deployment. Start at 250ms and back off to 2s.
     */
    private static final long POLL_INITIAL_MS = 250L;
    private static final long POLL_MAX_MS = 2_000L;

    private final AwsClientProvider awsClientProvider;

    /**
     * Hard ceiling on a single query. A monthly service aggregate over partitioned
     * Parquet returns in a couple of seconds; anything approaching this is a
     * missing partition filter doing a full-table scan, and the caller is better
     * served falling back to Cost Explorer than blocking a request thread.
     */
    @Value("${xamops.cur.query-timeout-seconds:60}")
    private int queryTimeoutSeconds;

    public CurAthenaQueryService(AwsClientProvider awsClientProvider) {
        this.awsClientProvider = awsClientProvider;
    }

    /**
     * Executes {@code sql} against the source's table and returns rows keyed by
     * column name.
     *
     * @param account the account the query is being run for. Used only to assume a
     *                role when the CUR lives in the customer's account; ignored for
     *                a XamOps-owned source. Never used to scope the query — the
     *                account filter is part of the SQL, built in
     *                {@link CurCostDataSource}.
     */
    public List<Map<String, String>> query(CurSource source, CloudAccount account, String sql) {
        AthenaClient athena = resolveClient(source, account);

        String queryExecutionId = submit(athena, source, sql);
        waitForCompletion(athena, queryExecutionId, source);
        return fetchAllRows(athena, queryExecutionId);
    }

    /**
     * Picks the credentials for a source.
     *
     * <p>Ownership alone is not enough to decide: XamOps can operate several
     * management/payer accounts, and only the one the application runs in is
     * reachable with host credentials. A second MPA is a separate AWS account and
     * needs its role assumed, which is why {@code assumeRoleArn} is checked before
     * falling back to host credentials.
     */
    private AthenaClient resolveClient(CurSource source, CloudAccount account) {
        if (source.isXamopsOwned()) {
            if (source.getAssumeRoleArn() != null && !source.getAssumeRoleArn().isBlank()) {
                return awsClientProvider.getRoleAthenaClient(
                        source.getAssumeRoleArn(), source.getAssumeRoleExternalId(), source.getRegion());
            }
            return awsClientProvider.getHostAthenaClient(source.getRegion());
        }
        if (account == null) {
            throw new IllegalStateException(
                    "CUR source " + source.getId() + " is customer-owned but no account was supplied to assume");
        }
        return awsClientProvider.getAthenaClient(account, source.getRegion());
    }

    private String submit(AthenaClient athena, CurSource source, String sql) {
        StartQueryExecutionRequest.Builder request = StartQueryExecutionRequest.builder()
                .queryString(sql)
                .queryExecutionContext(QueryExecutionContext.builder()
                        .database(source.getAthenaDatabase())
                        .build())
                .workGroup(source.getAthenaWorkgroup());

        // A workgroup can enforce its own output location, in which case passing one
        // is rejected outright. Only set it when the source carries one.
        if (source.getAthenaResultsLocation() != null && !source.getAthenaResultsLocation().isBlank()) {
            request.resultConfiguration(ResultConfiguration.builder()
                    .outputLocation(source.getAthenaResultsLocation())
                    .build());
        }

        String id = athena.startQueryExecution(request.build()).queryExecutionId();
        logger.debug("[CUR] Submitted Athena query {} against {}", id, source.qualifiedTable());
        return id;
    }

    private void waitForCompletion(AthenaClient athena, String queryExecutionId, CurSource source) {
        long deadline = System.currentTimeMillis() + (queryTimeoutSeconds * 1000L);
        long sleepMs = POLL_INITIAL_MS;

        while (true) {
            QueryExecution execution = athena.getQueryExecution(
                    GetQueryExecutionRequest.builder().queryExecutionId(queryExecutionId).build())
                    .queryExecution();

            QueryExecutionState state = execution.status().state();
            if (state == QueryExecutionState.SUCCEEDED) {
                QueryExecutionStatistics stats = execution.statistics();
                if (stats != null && stats.dataScannedInBytes() != null) {
                    // Scan volume is the whole cost model for this feature. Logged at debug so a
                    // query that starts scanning gigabytes — a dropped partition filter — is
                    // diagnosable without turning on Athena's own logging.
                    logger.debug("[CUR] Query {} succeeded, scanned {} bytes in {} ms",
                            queryExecutionId, stats.dataScannedInBytes(), stats.totalExecutionTimeInMillis());
                }
                return;
            }
            if (state == QueryExecutionState.FAILED || state == QueryExecutionState.CANCELLED) {
                String reason = execution.status().stateChangeReason();
                throw new CurQueryException(
                        "Athena query " + queryExecutionId + " " + state + ": " + reason);
            }

            if (System.currentTimeMillis() > deadline) {
                // Stop billing for a query nobody is waiting for any more.
                try {
                    athena.stopQueryExecution(
                            StopQueryExecutionRequest.builder().queryExecutionId(queryExecutionId).build());
                } catch (Exception stopEx) {
                    logger.warn("[CUR] Could not cancel timed-out query {}: {}",
                            queryExecutionId, stopEx.getMessage());
                }
                throw new CurQueryException("Athena query " + queryExecutionId + " exceeded "
                        + queryTimeoutSeconds + "s against " + source.qualifiedTable());
            }

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CurQueryException("Interrupted waiting for Athena query " + queryExecutionId);
            }
            sleepMs = Math.min(sleepMs * 2, POLL_MAX_MS);
        }
    }

    private List<Map<String, String>> fetchAllRows(AthenaClient athena, String queryExecutionId) {
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> columns = null;
        String nextToken = null;

        do {
            GetQueryResultsResponse response = athena.getQueryResults(
                    GetQueryResultsRequest.builder()
                            .queryExecutionId(queryExecutionId)
                            .nextToken(nextToken)
                            .build());

            List<Row> pageRows = response.resultSet().rows();
            int startIndex = 0;

            if (columns == null) {
                columns = response.resultSet().resultSetMetadata().columnInfo().stream()
                        .map(ColumnInfo::name)
                        .toList();
                // Athena repeats the column names as the first data row of the FIRST page
                // only. Skipping it unconditionally on every page would drop a real row.
                startIndex = 1;
            }

            for (int i = startIndex; i < pageRows.size(); i++) {
                List<Datum> data = pageRows.get(i).data();
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < columns.size() && c < data.size(); c++) {
                    row.put(columns.get(c), data.get(c).varCharValue());
                }
                rows.add(row);
            }

            nextToken = response.nextToken();
        } while (nextToken != null);

        return rows;
    }

    /** Signals that a CUR read failed and the caller should fall back to Cost Explorer. */
    public static class CurQueryException extends RuntimeException {
        public CurQueryException(String message) {
            super(message);
        }
    }
}
