package com.xammer.cloud.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xammer.cloud.security.ClientUserDetails;
import org.springframework.stereotype.Component;

@Component
public class ClientUserDetailsJacksonModule extends SimpleModule {
    
    public ClientUserDetailsJacksonModule() {
        super("ClientUserDetailsModule");
        // Register the mixin for ClientUserDetails
        setMixInAnnotation(ClientUserDetails.class, 
            com.xammer.cloud.security.ClientUserDetailsMixin.class);
    }
}
