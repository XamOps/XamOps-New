package com.xammer.cloud.dto.cloudidr;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudIdrAuthResponse {

    @JsonProperty("token")
    private String token; // Changed from accessToken

    @JsonProperty("userId")
    private String userId; // Changed from refreshToken

    // Keep these for backward compatibility
    public String getAccessToken() {
        return token;
    }

    public void setAccessToken(String token) {
        this.token = token;
    }

    public String getRefreshToken() {
        return userId; // Not actually a refresh token, but keeping API compatible
    }

    public void setRefreshToken(String userId) {
        this.userId = userId;
    }
}
