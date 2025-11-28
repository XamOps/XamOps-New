package com.xammer.billops.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.ClientUserDetailsMixin;
import org.springframework.stereotype.Component;

/**
 * Registers the Jackson Mixin for ClientUserDetails.
 * This is CRITICAL for deserializing sessions shared with XamOps.
 */
@Component
public class ClientUserDetailsJacksonModule extends SimpleModule {
    
    public ClientUserDetailsJacksonModule() {
        super("ClientUserDetailsModule");
        // Register the same mixin used by XamOps
        setMixInAnnotation(ClientUserDetails.class, ClientUserDetailsMixin.class);
    }
}