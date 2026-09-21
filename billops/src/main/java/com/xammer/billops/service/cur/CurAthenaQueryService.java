package com.xammer.billops.service.cur;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.CurSourceView;
import com.xammer.billops.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.ColumnInfo;
import software.amazon.awssdk.services.athena.model.Datum;
import software.amazon.awssdk.services.athena.model.GetQueryExecutionRequest;
import software.amazon.awssdk.services.athena.model.GetQueryResultsRequest;
import software.amazon.awssdk.services.athena.model.GetQueryResultsResponse;
import software.amazon.awssdk.services.athena.model.QueryExecution;
import software.amazon.awssdk.services.athena.model.QueryExecutionContext;
import software.amazon.awssdk.services.athena.model.QueryExecutionState;
import software.amazon.awssdk.services.athena.model.QueryExecutionStatistics;
import software.amazon.awssdk.services.athena.model.ResultConfiguration;
import software.amazon.awssdk.services.athena.model.Row;
import software.amazon.awssdk.services.athena.model.StartQueryExecutionRequest;
import software.amazon.awssdk.services.athena.model.StopQueryExecutionRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a SQL statement against a customer's CUR table and returns the rows.
 *
 * <p>Deliberately a near-copy of xamops-service's service of the same name rather
 * than a shared library. The two applications share a database and nothing else —
 * no common jar, no HTTP between them — so the choice was duplicating about a
 * hundred lines of poll-and-page or introducing the first cross-service call in the
 * codebase on the path that issues customer invoices. The duplicated part is the
 * stable bit: Athena's submit/poll/paginate contract has not changed in years,
 * while the SQL, which is the part that actually evolves, lives in one place per
 * application.
 *
 * <p>Timeout, backoff and cancel-on-timeout behave as they do there, for the same
 * reasons: a monthly aggregate over partitioned Parquet returns in seconds, so a
 * query approaching the ceiling is scanning far more than it should and the caller
 * is better off being told than blocked.
 */
@Service
public class CurAthenaQueryService {

    private static final Logger logger = LoggerFactory.getLogger(CurAthenaQueryService.class);

    private static final long POLL_INITIAL_MS = 250L;
    private static final long POLL_MAX_MS = 2_000L;

    private final AwsClientProvider awsClientProvider;

    @Value("${billops.cur.query-timeout-seconds:60}")
    private int queryTimeoutSeconds;

    public CurAthenaQueryService(AwsClientProvider awsClientProvider) {
        this.awsClientProvider = awsClientProvider;
    }

    /** Executes {@code sql} and returns rows keyed by column name. */
    public List<Map<String, String>> query(CurSourceView source, CloudAccount account, String sql) {
        String region = source.getRegion() == null || source.getRegion().isBlank()
                ? "us-east-1"
                : source.getRegion();
        AthenaClient athena = awsClientProvider.getAthenaClient(account, region);

        String queryExecutionId = submit(athena, source, sql);
        waitForCompletion(athena, queryExecutionId, source);
        return fetchAllRows(athena, queryExecutionId);
    }

    private String submit(AthenaClient athena, CurSourceView source, String sql) {
        StartQueryExecutionRequest.Builder request = StartQueryExecutionRequest.builder()
                .queryString(sql)
                .queryExecutionContext(QueryExecutionContext.builder()
                        .database(source.getAthenaDatabase())
                        .build())
                .workGroup(source.getAthenaWorkgroup() == null ? "primary" : source.getAthenaWorkgroup());

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

    private void waitForCompletion(AthenaClient athena, String queryExecutionId, CurSourceView source) {
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
                    // Scan volume is the whole cost model here, and the customer pays it.
                    logger.debug("[CUR] Query {} succeeded, scanned {} bytes in {} ms",
                            queryExecutionId, stats.dataScannedInBytes(), stats.totalExecutionTimeInMillis());
                }
                return;
            }
            if (state == QueryExecutionState.FAILED || state == QueryExecutionState.CANCELLED) {
                throw new CurQueryException(
                        "Athena query " + state + ": " + execution.status().stateChangeReason());
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
                throw new CurQueryException("Athena query exceeded " + queryTimeoutSeconds
                        + "s against " + source.qualifiedTable());
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

    /** A CUR read that did not complete. Never silently downgraded to Cost Explorer. */
    public static class CurQueryException extends RuntimeException {
        public CurQueryException(String message) {
            super(message);
        }
    }
}
