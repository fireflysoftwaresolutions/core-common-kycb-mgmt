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


package io.fireflysoftware.core.kycb.web.controllers.identity.v1;

import io.fireflysoftware.core.kycb.interfaces.dtos.sanctions.v1.SanctionsQuestionnaireDTO;
import io.fireflysoftware.core.kycb.core.services.sanctions.v1.SanctionsQuestionnaireService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/identity/parties/{partyId}/sanctions-questionnaire")
@Tag(name = "Sanctions Questionnaire", description = "API for managing sanctions and embargo questionnaires")
public class SanctionsQuestionnaireController {

    @Autowired
    private SanctionsQuestionnaireService sanctionsQuestionnaireService;

    @GetMapping("/{sanctionsQuestionnaireId}")
    @Operation(
            summary = "Get sanctions questionnaire",
            description = "Retrieves a sanctions questionnaire by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved sanctions questionnaire",
                            content = @Content(schema = @Schema(implementation = SanctionsQuestionnaireDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Sanctions questionnaire not found"
                    )
            }
    )
    public Mono<ResponseEntity<SanctionsQuestionnaireDTO>> getSanctionsQuestionnaire(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the sanctions questionnaire", required = true)
            @PathVariable UUID sanctionsQuestionnaireId
    ) {
        return sanctionsQuestionnaireService.findById(sanctionsQuestionnaireId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/latest")
    @Operation(
            summary = "Get latest sanctions questionnaire",
            description = "Retrieves the latest sanctions questionnaire for a party",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved latest sanctions questionnaire",
                            content = @Content(schema = @Schema(implementation = SanctionsQuestionnaireDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "No sanctions questionnaire found for the party"
                    )
            }
    )
    public Mono<ResponseEntity<SanctionsQuestionnaireDTO>> getLatestSanctionsQuestionnaire(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId
    ) {
        return sanctionsQuestionnaireService.findLatestByPartyId(partyId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(
            summary = "List sanctions questionnaires",
            description = "Retrieves all sanctions questionnaires for a party",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved sanctions questionnaires",
                            content = @Content(schema = @Schema(implementation = SanctionsQuestionnaireDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<Flux<SanctionsQuestionnaireDTO>>> listSanctionsQuestionnaires(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId
    ) {
        return Mono.just(ResponseEntity.ok(sanctionsQuestionnaireService.findByPartyId(partyId)));
    }

    @PostMapping
    @Operation(
            summary = "Create sanctions questionnaire",
            description = "Creates a new sanctions questionnaire for a specific party",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully created sanctions questionnaire",
                            content = @Content(schema = @Schema(implementation = SanctionsQuestionnaireDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<SanctionsQuestionnaireDTO>> createSanctionsQuestionnaire(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "Sanctions questionnaire data", required = true)
            @RequestBody SanctionsQuestionnaireDTO sanctionsQuestionnaireDTO
    ) {
        sanctionsQuestionnaireDTO.setPartyId(partyId);
        return sanctionsQuestionnaireService.create(sanctionsQuestionnaireDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @PatchMapping("/{sanctionsQuestionnaireId}")
    @Operation(
            summary = "Update sanctions questionnaire",
            description = "Updates a sanctions questionnaire by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated sanctions questionnaire",
                            content = @Content(schema = @Schema(implementation = SanctionsQuestionnaireDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Sanctions questionnaire not found"
                    )
            }
    )
    public Mono<ResponseEntity<SanctionsQuestionnaireDTO>> updateSanctionsQuestionnaire(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the sanctions questionnaire", required = true)
            @PathVariable UUID sanctionsQuestionnaireId,
            @Parameter(description = "Updated sanctions questionnaire data", required = true)
            @RequestBody SanctionsQuestionnaireDTO sanctionsQuestionnaireDTO
    ) {
        sanctionsQuestionnaireDTO.setPartyId(partyId);
        return sanctionsQuestionnaireService.update(sanctionsQuestionnaireId, sanctionsQuestionnaireDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{sanctionsQuestionnaireId}")
    @Operation(
            summary = "Delete sanctions questionnaire",
            description = "Deletes a sanctions questionnaire by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted sanctions questionnaire"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Sanctions questionnaire not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deleteSanctionsQuestionnaire(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the sanctions questionnaire", required = true)
            @PathVariable UUID sanctionsQuestionnaireId
    ) {
        return sanctionsQuestionnaireService.delete(sanctionsQuestionnaireId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}