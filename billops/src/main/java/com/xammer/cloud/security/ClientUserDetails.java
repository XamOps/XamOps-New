package com.xammer.cloud.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.Collections;

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientUserDetails extends User {

    private final Long clientId;
    private final Long id;

    @JsonCreator
    public ClientUserDetails(
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("authorities") Collection<? extends GrantedAuthority> authorities,
            @JsonProperty("clientId") Long clientId,
            @JsonProperty("id") Long id) {

        // ✅ FIX: Handle null passwords from Redis session to prevent crash
        super(
                username,
                (password != null ? password : "[PROTECTED]"),
                (authorities != null ? authorities : Collections.emptyList()));

        this.clientId = clientId;
        this.id = id;
    }

    public Long getClientId() {
        return clientId;
    }

    public Long getId() {
        return id;
    }
}