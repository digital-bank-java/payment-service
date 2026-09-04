package com.digitalbank.paymentservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class JwtDecoderConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(JwtDecoderConfiguration.class);

    @Test
    void failsWhenJwkSetUriIsConfiguredWithoutIssuerUri() {
        contextRunner
                .withPropertyValues("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.test/jwks")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "spring.security.oauth2.resourceserver.jwt.issuer-uri must be configured"));
    }

    @Test
    void createsHmacDecoderFromSharedSitSecretWhenOidcIsNotConfigured() {
        contextRunner
                .withPropertyValues(
                        "auth.jwt.secret=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                        "auth.jwt.issuer=digital-bank-auth")
                .run(context -> assertThat(context).hasSingleBean(JwtDecoder.class));
    }
}
