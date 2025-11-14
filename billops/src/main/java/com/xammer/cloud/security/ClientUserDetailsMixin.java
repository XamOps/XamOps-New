package com.xammer.cloud.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.security.core.GrantedAuthority;
import java.util.Collection;

public abstract class ClientUserDetailsMixin {
    
    @JsonCreator
    public ClientUserDetailsMixin(
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("authorities") Collection<? extends GrantedAuthority> authorities,
            @JsonProperty("clientId") Long clientId,
            @JsonProperty("id") Long id) {}
}
