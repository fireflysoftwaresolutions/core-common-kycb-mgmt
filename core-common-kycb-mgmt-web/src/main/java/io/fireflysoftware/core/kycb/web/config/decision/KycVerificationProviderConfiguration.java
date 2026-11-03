/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.web.config.decision;

import io.fireflysoftware.core.kycb.core.providers.verification.KycVerificationProvider;
import io.fireflysoftware.core.kycb.core.providers.verification.impl.NoOpKycVerificationProviderImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class KycVerificationProviderConfiguration {
    @Bean @ConditionalOnMissingBean(KycVerificationProvider.class)
    KycVerificationProvider kycVerificationProvider() { return new NoOpKycVerificationProviderImpl(); }
}
