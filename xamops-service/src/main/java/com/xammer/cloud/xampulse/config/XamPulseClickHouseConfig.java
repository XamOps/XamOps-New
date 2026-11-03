package com.xammer.cloud.xampulse.config;

import com.xammer.cloud.xampulse.service.query.ClickHouseGateway;
import com.xammer.cloud.xampulse.service.query.JdbcClickHouseGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ClickHouse wiring. Active only when {@code xampulse.enabled=true}. */
@Configuration("xpClickHouseConfig")
@EnableConfigurationProperties(XamPulseProperties.class)
public class XamPulseClickHouseConfig {

    @Bean(name = "xpClickHouseConnections", destroyMethod = "close")
    @ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
    public ClickHouseConnections xpClickHouseConnections(XamPulseProperties props) {
        return new ClickHouseConnections(props.getClickhouse());
    }

    @Bean(name = "xpClickHouseGateway")
    @ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
    public ClickHouseGateway xpClickHouseGateway(ClickHouseConnections connections) {
        return new JdbcClickHouseGateway(connections);
    }

    @Bean(name = "xpClickHouseMutations")
    @ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
    public com.xammer.cloud.xampulse.service.platform.ClickHouseMutations xpClickHouseMutations(ClickHouseConnections connections) {
        return new com.xammer.cloud.xampulse.service.platform.JdbcClickHouseMutations(connections);
    }

    @Bean(name = "xpClickHouseWrites")
    @ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
    public com.xammer.cloud.xampulse.service.platform.ClickHouseWrites xpClickHouseWrites(ClickHouseConnections connections) {
        return new com.xammer.cloud.xampulse.service.platform.JdbcClickHouseWrites(connections);
    }
}
