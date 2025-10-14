package com.xammer.billops.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@Service
public class GcpClientProvider {

    /**
     * Creates a BigQuery client authenticated with the provided service account key.
     *
     * @param projectId The GCP project ID.
     * @param serviceAccountKeyJson The JSON string of the service account key.
     * @return An initialized BigQuery client.
     * @throws IOException If the credentials cannot be parsed.
     */
    public BigQuery createBigQueryClient(String projectId, String serviceAccountKeyJson) throws IOException {
        GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(serviceAccountKeyJson.getBytes()));
        return BigQueryOptions.newBuilder()
                .setProjectId(projectId)
                .setCredentials(credentials)
                .build()
                .getService();
    }
}