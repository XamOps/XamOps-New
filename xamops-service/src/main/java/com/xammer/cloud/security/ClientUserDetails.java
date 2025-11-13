package com.xammer.cloud.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.Collection;

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
        super(username, password, authorities);
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
