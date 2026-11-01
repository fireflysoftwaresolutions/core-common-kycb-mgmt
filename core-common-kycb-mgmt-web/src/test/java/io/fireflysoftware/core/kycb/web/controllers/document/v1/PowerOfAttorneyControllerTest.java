/*
 * Copyright 2025 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.fireflysoftware.core.kycb.web.controllers.document.v1;

import io.fireflysoftware.core.kycb.core.services.power.v1.PowerOfAttorneyService;
import io.fireflysoftware.core.kycb.interfaces.dtos.power.v1.PowerOfAttorneyDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Web-layer test for {@link PowerOfAttorneyController#addPowerOfAttorney(PowerOfAttorneyDTO)}.
 *
 * <p>Verifies that the controller's POST handler accepts the new BE-5c fields
 * (email, signingAuthorized, isPep), forwards them to the service unchanged,
 * and returns them in the response with HTTP 201 Created.
 *
 * <p>This is a pure unit test (no Spring context) — the service is mocked and
 * injected via {@link ReflectionTestUtils} because the controller uses field
 * injection via {@code @Autowired}.
 */
@ExtendWith(MockitoExtension.class)
class PowerOfAttorneyControllerTest {

    @Mock
    private PowerOfAttorneyService service;

    private PowerOfAttorneyController controller;

    @BeforeEach
    void setUp() {
        controller = new PowerOfAttorneyController();
        ReflectionTestUtils.setField(controller, "powerOfAttorneyService", service);
    }

    @Test
    void addPowerOfAttorney_persistsNewSignerFields() {
        UUID partyId = UUID.randomUUID();
        UUID attorneyId = UUID.randomUUID();
        UUID corpDocId = UUID.randomUUID();

        PowerOfAttorneyDTO request = new PowerOfAttorneyDTO();
        request.setCorporateDocumentId(corpDocId);
        request.setPartyId(partyId);
        request.setAttorneyId(attorneyId);
        request.setPowerType("LIMITED");
        request.setPowerScope("Sign on behalf of the company");
        request.setJointSignatureRequired(false);
        request.setFinancialLimit(new BigDecimal("50000.00"));
        request.setCurrency("EUR");
        request.setEffectiveDate(LocalDateTime.now());
        request.setIsVerified(false);
        request.setIsPoaCompleted(false);
        // New BE-5c fields
        request.setEmail("attorney@example.com");
        request.setSigningAuthorized(Boolean.TRUE);
        request.setIsPep(Boolean.FALSE);

        PowerOfAttorneyDTO created = new PowerOfAttorneyDTO();
        created.setPowerOfAttorneyId(UUID.randomUUID());
        created.setCorporateDocumentId(corpDocId);
        created.setPartyId(partyId);
        created.setAttorneyId(attorneyId);
        created.setPowerType("LIMITED");
        created.setEmail("attorney@example.com");
        created.setSigningAuthorized(Boolean.TRUE);
        created.setIsPep(Boolean.FALSE);

        when(service.create(any(PowerOfAttorneyDTO.class))).thenReturn(Mono.just(created));

        Mono<ResponseEntity<PowerOfAttorneyDTO>> result = controller.addPowerOfAttorney(request);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                    PowerOfAttorneyDTO body = response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.getEmail()).isEqualTo("attorney@example.com");
                    assertThat(body.getSigningAuthorized()).isTrue();
                    assertThat(body.getIsPep()).isFalse();
                })
                .verifyComplete();

        // Verify the service receives the new fields exactly as submitted.
        ArgumentCaptor<PowerOfAttorneyDTO> captor = ArgumentCaptor.forClass(PowerOfAttorneyDTO.class);
        verify(service).create(captor.capture());
        PowerOfAttorneyDTO forwarded = captor.getValue();
        assertThat(forwarded.getEmail()).isEqualTo("attorney@example.com");
        assertThat(forwarded.getSigningAuthorized()).isTrue();
        assertThat(forwarded.getIsPep()).isFalse();
    }
}
