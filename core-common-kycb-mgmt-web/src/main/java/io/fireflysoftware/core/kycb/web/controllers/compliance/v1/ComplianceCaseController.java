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
import io.fireflysoftware.core.kycb.core.services.compliance.v1.ComplianceCaseService;
import io.fireflysoftware.core.kycb.interfaces.dtos.compliance.v1.ComplianceCaseDTO;
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
@RequestMapping("/api/v1/compliance/cases")
@Tag(name = "Compliance Cases", description = "API for managing compliance cases")
public class ComplianceCaseController {

    @Autowired
    private ComplianceCaseService complianceCaseService;

    @GetMapping
    @Operation(
            summary = "List compliance cases",
            description = "Retrieves all compliance cases with filtering capabilities",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved compliance cases",
                            content = @Content(schema = @Schema(implementation = PaginationResponse.class))
                    )
            }
    )
    public Mono<ResponseEntity<PaginationResponse<ComplianceCaseDTO>>> listComplianceCases(
            @Parameter(description = "Filter criteria")
            @ModelAttribute FilterRequest<ComplianceCaseDTO> filterRequest
    ) {
        return complianceCaseService.findAll(filterRequest)
                .map(ResponseEntity::ok);
    }

    @PostMapping
    @Operation(
            summary = "Create new compliance case",
            description = "Creates a new compliance case",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully created compliance case",
                            content = @Content(schema = @Schema(implementation = ComplianceCaseDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<ComplianceCaseDTO>> createComplianceCase(
            @Parameter(description = "Compliance case data", required = true)
            @RequestBody ComplianceCaseDTO complianceCaseDTO
    ) {
        return complianceCaseService.create(complianceCaseDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @GetMapping("/{caseId}")
    @Operation(
            summary = "Get specific case",
            description = "Retrieves a specific compliance case by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved compliance case",
                            content = @Content(schema = @Schema(implementation = ComplianceCaseDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Compliance case not found"
                    )
            }
    )
    public Mono<ResponseEntity<ComplianceCaseDTO>> getComplianceCase(
            @Parameter(description = "ID of the case", required = true)
            @PathVariable UUID caseId
    ) {
        return complianceCaseService.getById(caseId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{caseId}")
    @Operation(
            summary = "Update case",
            description = "Updates an existing compliance case",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated compliance case",
                            content = @Content(schema = @Schema(implementation = ComplianceCaseDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Compliance case not found"
                    )
            }
    )
    public Mono<ResponseEntity<ComplianceCaseDTO>> updateComplianceCase(
            @Parameter(description = "ID of the case", required = true)
            @PathVariable UUID caseId,
            @Parameter(description = "Updated compliance case data", required = true)
            @RequestBody ComplianceCaseDTO complianceCaseDTO
    ) {
        return complianceCaseService.update(caseId, complianceCaseDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{caseId}")
    @Operation(
            summary = "Delete case",
            description = "Deletes a compliance case",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted compliance case"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Compliance case not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deleteComplianceCase(
            @Parameter(description = "ID of the case", required = true)
            @PathVariable UUID caseId
    ) {
        return complianceCaseService.delete(caseId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
