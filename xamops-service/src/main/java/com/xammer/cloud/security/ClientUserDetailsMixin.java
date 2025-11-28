package com.xammer.cloud.security;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize; // ✅ Import this
import com.fasterxml.jackson.databind.JsonDeserializer; // ✅ Import this
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

// ✅ 1. Define Type Info to ensure class name is saved
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")

// ✅ 2. CRITICAL FIX: Disable the inherited 'UserDeserializer' from Spring
// Security
// This forces Jackson to use the @JsonCreator below instead of the parent's
// logic
@JsonDeserialize(using = JsonDeserializer.None.class)

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class ClientUserDetailsMixin {

    @JsonCreator
    public ClientUserDetailsMixin(
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("authorities") Collection<? extends GrantedAuthority> authorities,
            @JsonProperty("clientId") Long clientId,
            @JsonProperty("id") Long id) {
    }
}