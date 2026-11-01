package io.fireflysoftware.core.kycb.web.reproofing.impl;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.util.ClassUtils;

@Configuration(proxyBeanMethods = false)
public class ReproofingExistingSecurityGuardConfigurationImpl {
    @Bean
    public SmartInitializingSingleton reproofingExistingSecurityGuard(
            ApplicationContext context, Environment environment) {
        return () -> {
            boolean optionalResourceServerPresent = ClassUtils.isPresent(
                    "org.fireflyframework.security.rs.ResourceServerAutoConfiguration",
                    context.getClassLoader());
            boolean genericPolicyEnabled = environment.getProperty(
                    "firefly.security.resource-server.enabled", Boolean.class, true);
            if (!optionalResourceServerPresent || !genericPolicyEnabled) {
                return;
            }
            String existingName = environment.getProperty(
                    "firefly.reproofing.existing-security-chain-bean", "fireflySecurityWebFilterChain");
            if (existingName.equals("reproofingSecurityWebFilterChain")
                    || !context.containsBean(existingName)
                    || !(context.getBean(existingName) instanceof SecurityWebFilterChain)) {
                throw new IllegalStateException(
                        "Explicit existing resource-server chain composition is required");
            }
        };
    }
}
