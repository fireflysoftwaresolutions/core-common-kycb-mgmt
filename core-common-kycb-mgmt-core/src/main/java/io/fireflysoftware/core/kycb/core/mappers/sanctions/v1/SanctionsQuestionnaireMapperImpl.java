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


package io.fireflysoftware.core.kycb.core.mappers.sanctions.v1;

import io.fireflysoftware.core.kycb.interfaces.dtos.sanctions.v1.SanctionsQuestionnaireDTO;
import io.fireflysoftware.core.kycb.models.entities.sanctions.v1.SanctionsQuestionnaire;
import org.springframework.stereotype.Component;

/**
 * Mapper for converting between SanctionsQuestionnaire entity and DTO.
 */
@Component("sanctionsQuestionnaireMapper")
public class SanctionsQuestionnaireMapperImpl {

    /**
     * Convert entity to DTO.
     *
     * @param entity The entity to convert
     * @return The converted DTO
     */
    public SanctionsQuestionnaireDTO toDTO(SanctionsQuestionnaire entity) {
        if (entity == null) {
            return null;
        }

        return SanctionsQuestionnaireDTO.builder()
                .sanctionsQuestionnaireId(entity.getSanctionsQuestionnaireId())
                .partyId(entity.getPartyId())
                .entitySanctionsQuestionnaire(entity.getEntitySanctionsQuestionnaire())
                .activityOutsideEu(entity.getActivityOutsideEu())
                .economicSanctions(entity.getEconomicSanctions())
                .residentCountriesSanctions(entity.getResidentCountriesSanctions())
                .involvedSanctions(entity.getInvolvedSanctions())
                .questionnaireDate(entity.getQuestionnaireDate())
                .build();
    }

    /**
     * Convert DTO to entity.
     *
     * @param dto The DTO to convert
     * @return The converted entity
     */
    public SanctionsQuestionnaire toEntity(SanctionsQuestionnaireDTO dto) {
        if (dto == null) {
            return null;
        }

        return SanctionsQuestionnaire.builder()
                .sanctionsQuestionnaireId(dto.getSanctionsQuestionnaireId())
                .partyId(dto.getPartyId())
                .entitySanctionsQuestionnaire(dto.getEntitySanctionsQuestionnaire())
                .activityOutsideEu(dto.getActivityOutsideEu())
                .economicSanctions(dto.getEconomicSanctions())
                .residentCountriesSanctions(dto.getResidentCountriesSanctions())
                .involvedSanctions(dto.getInvolvedSanctions())
                .questionnaireDate(dto.getQuestionnaireDate())
                .build();
    }
}