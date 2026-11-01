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
import io.fireflysoftware.core.kycb.core.services.industry.v1.IndustryRiskService;
import io.fireflysoftware.core.kycb.interfaces.dtos.industry.v1.IndustryRiskDTO;
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
@RequestMapping("/api/v1/compliance/industry-risks")
@Tag(name = "Industry Risks", description = "API for managing industry risk profiles")
public class IndustryRiskController {

    @Autowired
    private IndustryRiskService industryRiskService;

    @GetMapping
    @Operation(
            summary = "List industry risk profiles",
            description = "Retrieves all industry risk profiles with optional filtering",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved industry risk profiles",
                            content = @Content(schema = @Schema(implementation = PaginationResponse.class))
                    )
            }
    )
    public Mono<ResponseEntity<PaginationResponse<IndustryRiskDTO>>> listIndustryRisks(
            @Parameter(description = "Filter request", required = false)
            @ModelAttribute FilterRequest<IndustryRiskDTO> filterRequest
    ) {
        return industryRiskService.findAll(filterRequest)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{industryRiskId}")
    @Operation(
            summary = "Get specific industry risk profile",
            description = "Retrieves a specific industry risk profile by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved industry risk profile",
                            content = @Content(schema = @Schema(implementation = IndustryRiskDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Industry risk profile not found"
                    )
            }
    )
    public Mono<ResponseEntity<IndustryRiskDTO>> getIndustryRisk(
            @Parameter(description = "Industry risk ID", required = true)
            @PathVariable UUID industryRiskId
    ) {
        return industryRiskService.getById(industryRiskId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(
            summary = "Create industry risk profile",
            description = "Creates a new industry risk profile",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully created industry risk profile",
                            content = @Content(schema = @Schema(implementation = IndustryRiskDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<IndustryRiskDTO>> createIndustryRisk(
            @Parameter(description = "Industry risk profile data", required = true)
            @RequestBody IndustryRiskDTO industryRiskDTO
    ) {
        return industryRiskService.create(industryRiskDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @PatchMapping("/{industryRiskId}")
    @Operation(
            summary = "Update industry risk profile",
            description = "Updates an existing industry risk profile",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated industry risk profile",
                            content = @Content(schema = @Schema(implementation = IndustryRiskDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Industry risk profile not found"
                    )
            }
    )
    public Mono<ResponseEntity<IndustryRiskDTO>> updateIndustryRisk(
            @Parameter(description = "Industry risk ID", required = true)
            @PathVariable UUID industryRiskId,
            @Parameter(description = "Updated industry risk profile data", required = true)
            @RequestBody IndustryRiskDTO industryRiskDTO
    ) {
        return industryRiskService.update(industryRiskId, industryRiskDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{industryRiskId}")
    @Operation(
            summary = "Delete industry risk profile",
            description = "Deletes an industry risk profile",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted industry risk profile"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Industry risk profile not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deleteIndustryRisk(
            @Parameter(description = "Industry risk ID", required = true)
            @PathVariable UUID industryRiskId
    ) {
        return industryRiskService.delete(industryRiskId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}