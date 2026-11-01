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


package io.fireflysoftware.core.kycb.web.controllers.compliance.v1;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.kycb.core.services.risk.v1.RiskAssessmentService;
import io.fireflysoftware.core.kycb.interfaces.dtos.risk.v1.RiskAssessmentDTO;
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
import reactor.core.publisher.Mono;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/compliance/parties/{partyId}/risk-assessments")
@Tag(name = "Risk Assessment", description = "API for managing risk assessments")
public class RiskAssessmentController {

    @Autowired
    private RiskAssessmentService riskAssessmentService;

    @GetMapping
    @Operation(
            summary = "List risk assessments",
            description = "Retrieves all risk assessments for the specified party ID with filtering capabilities",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved risk assessments",
                            content = @Content(schema = @Schema(implementation = PaginationResponse.class))
                    )
            }
    )
    public Mono<ResponseEntity<PaginationResponse<RiskAssessmentDTO>>> listRiskAssessments(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "Filter criteria")
            @ModelAttribute FilterRequest<RiskAssessmentDTO> filterRequest
    ) {
        // Set party ID filter
        RiskAssessmentDTO filter = filterRequest.getFilters() != null ? filterRequest.getFilters() : new RiskAssessmentDTO();
        filter.setPartyId(partyId);
        filterRequest.setFilters(filter);

        return riskAssessmentService.findAll(filterRequest)
                .map(ResponseEntity::ok);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create new risk assessment",
            description = "Creates a new risk assessment for the specified party",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully created risk assessment",
                            content = @Content(schema = @Schema(implementation = RiskAssessmentDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<RiskAssessmentDTO>> createRiskAssessment(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "Risk assessment data", required = true)
            @RequestBody RiskAssessmentDTO riskAssessmentDTO
    ) {
        riskAssessmentDTO.setPartyId(partyId);
        return riskAssessmentService.create(riskAssessmentDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @GetMapping("/{assessmentId}")
    @Operation(
            summary = "Get specific assessment",
            description = "Retrieves a specific risk assessment by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved risk assessment",
                            content = @Content(schema = @Schema(implementation = RiskAssessmentDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Risk assessment not found"
                    )
            }
    )
    public Mono<ResponseEntity<RiskAssessmentDTO>> getRiskAssessment(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the assessment", required = true)
            @PathVariable UUID assessmentId
    ) {
        return riskAssessmentService.getById(assessmentId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{assessmentId}")
    @Operation(
            summary = "Update risk assessment",
            description = "Updates an existing risk assessment",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated risk assessment",
                            content = @Content(schema = @Schema(implementation = RiskAssessmentDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Risk assessment not found"
                    )
            }
    )
    public Mono<ResponseEntity<RiskAssessmentDTO>> updateRiskAssessment(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the assessment", required = true)
            @PathVariable UUID assessmentId,
            @Parameter(description = "Updated risk assessment data", required = true)
            @RequestBody RiskAssessmentDTO riskAssessmentDTO
    ) {
        riskAssessmentDTO.setPartyId(partyId);
        return riskAssessmentService.update(assessmentId, riskAssessmentDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{assessmentId}")
    @Operation(
            summary = "Delete risk assessment",
            description = "Deletes a risk assessment",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted risk assessment"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Risk assessment not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deleteRiskAssessment(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the assessment", required = true)
            @PathVariable UUID assessmentId
    ) {
        return riskAssessmentService.delete(assessmentId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
