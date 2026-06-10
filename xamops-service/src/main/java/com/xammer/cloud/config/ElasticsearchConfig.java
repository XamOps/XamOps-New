package com.xammer.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * Elasticsearch configuration for DevOps script search functionality.
 * Configures connection settings and enables Elasticsearch repositories.
 * 
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.6.1 (Performance)
 */
@Configuration
@EnableElasticsearchRepositories(basePackages = "com.xammer.cloud.repository.elasticsearch")
public class ElasticsearchConfig extends ElasticsearchConfiguration {

    @Value("${elasticsearch.host:localhost}")
    private String elasticsearchHost;

    @Value("${elasticsearch.port:9200}")
    private int elasticsearchPort;

    @Value("${elasticsearch.username:}")
    private String elasticsearchUsername;

    @Value("${elasticsearch.password:}")
    private String elasticsearchPassword;

    @Value("${elasticsearch.socket-timeout:30000}")
    private long socketTimeout;

    @Value("${elasticsearch.connection-timeout:5000}")
    private long connectionTimeout;

    @Override
    public ClientConfiguration clientConfiguration() {
        if (elasticsearchUsername != null && !elasticsearchUsername.isEmpty() 
            && elasticsearchPassword != null && !elasticsearchPassword.isEmpty()) {
            return ClientConfiguration.builder()
                    .connectedTo(elasticsearchHost + ":" + elasticsearchPort)
                    .withSocketTimeout(socketTimeout)
                    .withConnectTimeout(connectionTimeout)
                    .withBasicAuth(elasticsearchUsername, elasticsearchPassword)
                    .build();
        }

        return ClientConfiguration.builder()
                .connectedTo(elasticsearchHost + ":" + elasticsearchPort)
                .withSocketTimeout(socketTimeout)
                .withConnectTimeout(connectionTimeout)
                .build();
    }
}
