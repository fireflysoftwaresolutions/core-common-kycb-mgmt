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


package io.fireflysoftware.core.kycb.core.services.sanctions.v1;

import io.fireflysoftware.core.kycb.core.mappers.sanctions.v1.SanctionsQuestionnaireMapperImpl;
import io.fireflysoftware.core.kycb.interfaces.dtos.sanctions.v1.SanctionsQuestionnaireDTO;
import io.fireflysoftware.core.kycb.interfaces.enums.sanctions.v1.EntitySanctionsQuestionnaireTypeEnum;
import io.fireflysoftware.core.kycb.models.entities.sanctions.v1.SanctionsQuestionnaire;
import io.fireflysoftware.core.kycb.models.repositories.sanctions.v1.SanctionsQuestionnaireRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class SanctionsQuestionnaireServiceImplTest {

    @Mock
    private SanctionsQuestionnaireRepository repository;

    @Mock
    private SanctionsQuestionnaireMapperImpl mapper;

    @InjectMocks
    private SanctionsQuestionnaireServiceImpl service;

    private SanctionsQuestionnaire entity;
    private SanctionsQuestionnaireDTO dto;
    private final UUID PARTY_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440100");
    private final UUID QUESTIONNAIRE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440004");

    @BeforeEach
    void setUp() {
        // Setup test data
        entity = SanctionsQuestionnaire.builder()
                .sanctionsQuestionnaireId(QUESTIONNAIRE_ID)
                .partyId(PARTY_ID)
                .entitySanctionsQuestionnaire(EntitySanctionsQuestionnaireTypeEnum.LEGAL_ENTITY_ONLY)
                .activityOutsideEu(true)
                .economicSanctions(false)
                .residentCountriesSanctions(false)
                .involvedSanctions(false)
                .questionnaireDate(LocalDateTime.now())
                .build();

        dto = SanctionsQuestionnaireDTO.builder()
                .sanctionsQuestionnaireId(QUESTIONNAIRE_ID)
                .partyId(PARTY_ID)
                .entitySanctionsQuestionnaire(EntitySanctionsQuestionnaireTypeEnum.LEGAL_ENTITY_ONLY)
                .activityOutsideEu(true)
                .economicSanctions(false)
                .residentCountriesSanctions(false)
                .involvedSanctions(false)
                .questionnaireDate(LocalDateTime.now())
                .build();
    }

    @Test
    void create_ShouldCreateSanctionsQuestionnaire() {
        // Arrange
        when(mapper.toEntity(any(SanctionsQuestionnaireDTO.class))).thenReturn(entity);
        when(repository.save(any(SanctionsQuestionnaire.class))).thenReturn(Mono.just(entity));
        when(mapper.toDTO(any(SanctionsQuestionnaire.class))).thenReturn(dto);

        // Act & Assert
        StepVerifier.create(service.create(dto))
                .expectNext(dto)
                .verifyComplete();

        verify(mapper).toEntity(any(SanctionsQuestionnaireDTO.class));
        verify(repository).save(any(SanctionsQuestionnaire.class));
        verify(mapper).toDTO(any(SanctionsQuestionnaire.class));
    }

    @Test
    void findById_ShouldReturnSanctionsQuestionnaire() {
        // Arrange
        when(repository.findById(QUESTIONNAIRE_ID)).thenReturn(Mono.just(entity));
        when(mapper.toDTO(entity)).thenReturn(dto);

        // Act & Assert
        StepVerifier.create(service.findById(QUESTIONNAIRE_ID))
                .expectNext(dto)
                .verifyComplete();

        verify(repository).findById(QUESTIONNAIRE_ID);
        verify(mapper).toDTO(entity);
    }

    @Test
    void findByPartyId_ShouldReturnSanctionsQuestionnaires() {
        // Arrange
        when(repository.findByPartyId(PARTY_ID)).thenReturn(Flux.just(entity));
        when(mapper.toDTO(entity)).thenReturn(dto);

        // Act & Assert
        StepVerifier.create(service.findByPartyId(PARTY_ID))
                .expectNext(dto)
                .verifyComplete();

        verify(repository).findByPartyId(PARTY_ID);
        verify(mapper).toDTO(entity);
    }

    @Test
    void findLatestByPartyId_ShouldReturnLatestSanctionsQuestionnaire() {
        // Arrange
        when(repository.findFirstByPartyIdOrderByQuestionnaireDateDesc(PARTY_ID)).thenReturn(Mono.just(entity));
        when(mapper.toDTO(entity)).thenReturn(dto);

        // Act & Assert
        StepVerifier.create(service.findLatestByPartyId(PARTY_ID))
                .expectNext(dto)
                .verifyComplete();

        verify(repository).findFirstByPartyIdOrderByQuestionnaireDateDesc(PARTY_ID);
        verify(mapper).toDTO(entity);
    }

    @Test
    void update_ShouldUpdateSanctionsQuestionnaire() {
        // Arrange
        when(repository.findById(QUESTIONNAIRE_ID)).thenReturn(Mono.just(entity));
        when(mapper.toEntity(dto)).thenReturn(entity);
        when(repository.save(entity)).thenReturn(Mono.just(entity));
        when(mapper.toDTO(entity)).thenReturn(dto);

        // Act & Assert
        StepVerifier.create(service.update(QUESTIONNAIRE_ID, dto))
                .expectNext(dto)
                .verifyComplete();

        verify(repository).findById(QUESTIONNAIRE_ID);
        verify(mapper).toEntity(dto);
        verify(repository).save(entity);
        verify(mapper).toDTO(entity);
    }

    @Test
    void delete_ShouldDeleteSanctionsQuestionnaire() {
        // Arrange
        when(repository.deleteById(QUESTIONNAIRE_ID)).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(service.delete(QUESTIONNAIRE_ID))
                .verifyComplete();

        verify(repository).deleteById(QUESTIONNAIRE_ID);
    }
}
