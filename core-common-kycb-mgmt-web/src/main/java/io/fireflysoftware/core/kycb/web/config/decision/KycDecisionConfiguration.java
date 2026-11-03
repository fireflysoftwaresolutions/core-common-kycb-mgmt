/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.web.config.decision;

import io.fireflysoftware.core.kycb.core.services.decision.*;
import io.fireflysoftware.core.kycb.core.services.decision.impl.*;
import io.fireflysoftware.core.kycb.core.services.decision.impl.CurrentKycDecisionAuthorityImpl.Operation;
import io.fireflysoftware.core.kycb.core.services.decision.party.KycPartyReader;
import io.fireflysoftware.core.kycb.core.services.decision.party.impl.ExchangedKycPartyReaderImpl;
import io.r2dbc.postgresql.*;
import io.r2dbc.postgresql.client.SSLMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.fireflyframework.core.transaction.OwnedLeaseConnectionFactoryImpl;
import org.fireflyframework.security.spi.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "firefly.kyc.manual.enabled", havingValue = "true")
public class KycDecisionConfiguration {
    private static final String PREFIX = "firefly.kyc.manual.";
    @Bean
    CurrentKycDecisionAuthorityImpl currentKycDecisionAuthority(SecurityContextPort principals,
            PolicyEvaluationPort policies, Environment environment) {
        if (!environment.getProperty("firefly.security.resource-server.enabled", Boolean.class, false)
                || !environment.getProperty("firefly.security.resource-server.strict-policy", Boolean.class, false))
            throw new IllegalStateException("Native strict resource server required for manual KYC");
        var clients = new EnumMap<Operation, Set<String>>(Operation.class);
        for (var operation : Operation.values()) clients.put(operation,
                Set.of(required(environment, "native." + operation.name().toLowerCase(Locale.ROOT) + "-client-ids").split(",", -1)));
        return new CurrentKycDecisionAuthorityImpl(principals, policies,
                new CurrentKycDecisionAuthorityImpl.Profile(required(environment, "native.issuer"), required(environment, "native.audience"), clients),
                Clock.systemUTC(), System::nanoTime);
    }
    @Bean
    KycPartyReader kycDecisionPartyReader(CurrentKycDecisionAuthorityImpl authority, SecurityContextPort principals,
            ReactiveJwtDecoder incoming, Environment environment) {
        boolean loopback = environment.getProperty(PREFIX + "customer.allow-loopback-http", Boolean.class, false);
        var settings = new ExchangedKycPartyReaderImpl.Settings(URI.create(required(environment, "customer.endpoint")),
                required(environment, "native.issuer"), required(environment, "native.audience"), required(environment, "customer.audience"),
                required(environment, "customer.registration-id"), required(environment, "customer.client-id"), loopback);
        URI jwks = URI.create(required(environment, "customer.jwk-set-uri"));secure(jwks, loopback);
        URI tokenEndpoint = URI.create(required(environment, "customer.token-uri"));secure(tokenEndpoint, loopback);
        var registration = ClientRegistration.withRegistrationId(settings.registrationId()).clientId(settings.clientId())
                .clientSecret(required(environment, "customer.client-secret")).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE).tokenUri(tokenEndpoint.toASCIIString())
                .issuerUri(settings.issuer()).scope("customer:parties:read").build();
        var registrations = new InMemoryReactiveClientRegistrationRepository(registration);
        var connector = new JdkClientHttpConnector(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2)).build());
        var transport = WebClient.builder().clientConnector(connector).codecs(codecs -> {
            codecs.defaultCodecs().maxInMemorySize(65536);codecs.defaultCodecs().enableLoggingRequestDetails(false);
        }).build();
        var outgoing = NimbusReactiveJwtDecoder.withJwkSetUri(jwks.toASCIIString()).webClient(transport).build();
        outgoing.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(settings.issuer()),
                new JwtTimestampValidator(Duration.ZERO)));
        return new ExchangedKycPartyReaderImpl(settings, authority, principals, registrations, incoming, outgoing, Clock.systemUTC());
    }
    @Bean("kycDecisionCommandSession")
    KycDecisionSession commandSession(Environment environment, ApplicationContext application,
            @Qualifier("r2dbcEntityTemplate") R2dbcEntityTemplate original) {
        var command = database(environment, "command");var reader = database(environment, "reader");
        if (!"firefly_audit_writer".equals(command.username()) || command.username().equals(reader.username())
                || !command.host().equals(reader.host()) || command.port() != reader.port() || !command.database().equals(reader.database()))
            throw new IllegalArgumentException("Exact original audit writer and distinct same-database reader required");
        return session(KycDecisionSession.Role.COMMAND, command, original, application);
    }
    @Bean("kycDecisionReaderSession")
    KycDecisionSession readerSession(Environment environment, ApplicationContext application,
            @Qualifier("r2dbcEntityTemplate") R2dbcEntityTemplate original) {
        return session(KycDecisionSession.Role.READER, database(environment, "reader"), original, application);
    }
    @Bean
    KycDecisionService kycDecisionService(CurrentKycDecisionAuthorityImpl authority, KycPartyReader parties,
            @Qualifier("kycDecisionCommandSession") KycDecisionSession command,
            @Qualifier("kycDecisionReaderSession") KycDecisionSession reader, Environment environment) {
        return new KycDecisionServiceImpl(authority, parties, command, reader,
                Duration.parse(required(environment, "maximum-validity")), ZoneId.of(required(environment, "source-expiry-zone")), Clock.systemUTC(), System::nanoTime, UUID::randomUUID);
    }
    private static KycDecisionSession session(KycDecisionSession.Role role, Database profile,
            R2dbcEntityTemplate original, ApplicationContext application) {
        var delegate = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(profile.host()).port(profile.port()).database(profile.database()).username(profile.username())
                .password(profile.password()).sslMode(profile.sslMode()).build());
        var factory = new OwnedLeaseConnectionFactoryImpl(delegate);var client = DatabaseClient.create(factory);
        var template = new R2dbcEntityTemplate(client, original.getDataAccessStrategy());
        template.setBeanFactory(application);template.setApplicationContext(application);
        return new KycDecisionSession(role, factory, client, template, new R2dbcTransactionManager(factory));
    }
    private record Database(String host, int port, String database, String username, String password, SSLMode sslMode) {
        @Override public String toString() { return "KycDecisionDatabaseProfile"; }
    }
    private static Database database(Environment environment, String role) {
        String prefix = "database." + role + ".";
        return new Database(required(environment, prefix + "host"), Integer.parseInt(required(environment, prefix + "port")),
                required(environment, prefix + "name"), required(environment, prefix + "username"), required(environment, prefix + "password"),
                SSLMode.valueOf(required(environment, prefix + "ssl-mode").toUpperCase(Locale.ROOT)));
    }
    private static void secure(URI uri, boolean loopback) {
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !("https".equals(uri.getScheme()) || loopback && "http".equals(uri.getScheme())
                    && Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(uri.getHost())))
            throw new IllegalArgumentException("Explicit secure Customer signing-key endpoint required");
    }
    private static String required(Environment environment, String key) { return environment.getRequiredProperty(PREFIX + key); }
}
