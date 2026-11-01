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
import io.fireflysoftware.core.kycb.models.entities.sanctions.v1.SanctionsQuestionnaire;
import io.fireflysoftware.core.kycb.models.repositories.sanctions.v1.SanctionsQuestionnaireRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Implementation of the SanctionsQuestionnaireService.
 */
@Slf4j
@Service
@Transactional
public class SanctionsQuestionnaireServiceImpl implements SanctionsQuestionnaireService {

    @Autowired
    private SanctionsQuestionnaireRepository sanctionsQuestionnaireRepository;

    @Autowired
    private SanctionsQuestionnaireMapperImpl mapper;

    @Override
    public Mono<SanctionsQuestionnaireDTO> create(SanctionsQuestionnaireDTO sanctionsQuestionnaireDTO) {
        log.debug("Creating sanctions questionnaire for party ID: {}", sanctionsQuestionnaireDTO.getPartyId());

        SanctionsQuestionnaire sanctionsQuestionnaire = mapper.toEntity(sanctionsQuestionnaireDTO);
        sanctionsQuestionnaire.setQuestionnaireDate(LocalDateTime.now());

        return sanctionsQuestionnaireRepository.save(sanctionsQuestionnaire)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<SanctionsQuestionnaireDTO> findById(UUID sanctionsQuestionnaireId) {
        log.debug("Finding sanctions questionnaire by ID: {}", sanctionsQuestionnaireId);

        return sanctionsQuestionnaireRepository.findById(sanctionsQuestionnaireId)
                .map(mapper::toDTO);
    }

    @Override
    public Flux<SanctionsQuestionnaireDTO> findByPartyId(UUID partyId) {
        log.debug("Finding sanctions questionnaires by party ID: {}", partyId);

        return sanctionsQuestionnaireRepository.findByPartyId(partyId)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<SanctionsQuestionnaireDTO> findLatestByPartyId(UUID partyId) {
        log.debug("Finding latest sanctions questionnaire by party ID: {}", partyId);

        return sanctionsQuestionnaireRepository.findFirstByPartyIdOrderByQuestionnaireDateDesc(partyId)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<SanctionsQuestionnaireDTO> update(UUID sanctionsQuestionnaireId, SanctionsQuestionnaireDTO sanctionsQuestionnaireDTO) {
        log.debug("Updating sanctions questionnaire with ID: {}", sanctionsQuestionnaireId);

        return sanctionsQuestionnaireRepository.findById(sanctionsQuestionnaireId)
                .flatMap(existingQuestionnaire -> {
                    SanctionsQuestionnaire updatedEntity = mapper.toEntity(sanctionsQuestionnaireDTO);
                    updatedEntity.setSanctionsQuestionnaireId(sanctionsQuestionnaireId);
                    updatedEntity.setQuestionnaireDate(LocalDateTime.now());
                    // Preserve creation date
                    updatedEntity.setDateCreated(existingQuestionnaire.getDateCreated());

                    return sanctionsQuestionnaireRepository.save(updatedEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> delete(UUID sanctionsQuestionnaireId) {
        log.debug("Deleting sanctions questionnaire with ID: {}", sanctionsQuestionnaireId);

        return sanctionsQuestionnaireRepository.deleteById(sanctionsQuestionnaireId);
    }
}
