package com.xammer.cloud.config;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.ClientUserDetailsMixin;

public class ClientUserDetailsJacksonModule extends SimpleModule {

    public ClientUserDetailsJacksonModule() {
        super(ClientUserDetailsJacksonModule.class.getName(),
                new Version(1, 0, 0, null, null, null));
    }

    @Override
    public void setupModule(SetupContext context) {
        context.setMixInAnnotations(ClientUserDetails.class, ClientUserDetailsMixin.class);
    }
}
