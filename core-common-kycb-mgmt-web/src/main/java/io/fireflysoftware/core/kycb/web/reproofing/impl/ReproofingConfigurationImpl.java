package io.fireflysoftware.core.kycb.web.reproofing.impl;

import io.fireflysoftware.core.kycb.core.reproofing.*;
import io.fireflysoftware.core.kycb.core.reproofing.impl.ReproofingServiceImpl;
import io.fireflysoftware.core.kycb.interfaces.reproofing.VerifiedReproofingEvidence;
import io.r2dbc.spi.ConnectionFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "firefly.reproofing.enabled", havingValue = "true")
public class ReproofingConfigurationImpl {
    @Bean
    public ReproofingService reproofingService(
            DatabaseClient db, ConnectionFactory factory, Environment env) {
        String prefix = "firefly.reproofing.";
        var nativeClient =
                new ReproofingRemoteClientImpl(
                        env.getRequiredProperty(prefix + "native.base-url"),
                        env.getRequiredProperty(prefix + "native.client-id"),
                        env.getRequiredProperty(prefix + "native.secret-file"));
        var evidenceClient =
                new ReproofingRemoteClientImpl(
                        env.getRequiredProperty(prefix + "evidence.base-url"),
                        env.getRequiredProperty(prefix + "evidence.client-id"),
                        env.getRequiredProperty(prefix + "evidence.secret-file"));
        return new ReproofingServiceImpl(
                db,
                TransactionalOperator.create(new R2dbcTransactionManager(factory)),
                new ReproofingNativeAdapterImpl(nativeClient),
                r -> evidenceClient.call("", r, VerifiedReproofingEvidence.class),
                env.getRequiredProperty(prefix + "provider-id"),
                env.getRequiredProperty(prefix + "policy-id"),
                env.getRequiredProperty(prefix + "issuer"),
                env.getRequiredProperty(prefix + "realm"),
                env.getRequiredProperty(prefix + "tenant"));
    }
}
