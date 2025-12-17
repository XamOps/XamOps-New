package com.xammer.cloud.config.mixin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.sql.SQLException;

@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class JDBCConnectionExceptionMixin {

    @JsonCreator
    public static org.hibernate.exception.JDBCConnectionException create(
            @JsonProperty("message") String message,
            @JsonProperty("cause") SQLException root,
            @JsonProperty("sql") String sql) {
        // If message is null, provide a default
        String msg = (message != null) ? message : "Unknown JDBC Connection Error";
        return new org.hibernate.exception.JDBCConnectionException(msg, root, sql);
    }
}
