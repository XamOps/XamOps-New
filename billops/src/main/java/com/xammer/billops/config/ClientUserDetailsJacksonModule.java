package com.xammer.billops.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.ClientUserDetailsMixin;
import org.springframework.security.jackson2.SecurityJackson2Modules;

public class ClientUserDetailsJacksonModule extends SimpleModule {

    public ClientUserDetailsJacksonModule() {
        super(ClientUserDetailsJacksonModule.class.getName(),
                new com.fasterxml.jackson.core.Version(1, 0, 0, null, null, null));
    }

    @Override
    public void setupModule(SetupContext context) {
        // Register the mixin to tell Jackson how to handle ClientUserDetails
        context.setMixInAnnotations(ClientUserDetails.class, ClientUserDetailsMixin.class);
    }
}