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
import io.fireflysoftware.core.kycb.core.services.aml.v1.AmlScreeningService;
import io.fireflysoftware.core.kycb.interfaces.dtos.aml.v1.AmlScreeningDTO;
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
@RequestMapping("/api/v1/compliance/parties/{partyId}/aml-screenings")
@Tag(name = "AML Screening", description = "API for managing AML screenings")
public class AmlScreeningController {

    @Autowired
    private AmlScreeningService amlScreeningService;

    @GetMapping
    @Operation(
            summary = "List AML screenings",
            description = "Retrieves all AML screenings for the specified party ID with filtering capabilities",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved AML screenings",
                            content = @Content(schema = @Schema(implementation = PaginationResponse.class))
                    )
            }
    )
    public Mono<ResponseEntity<PaginationResponse<AmlScreeningDTO>>> listAmlScreenings(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "Filter criteria")
            @ModelAttribute FilterRequest<AmlScreeningDTO> filterRequest
    ) {
        // Set party ID filter
        AmlScreeningDTO filter = filterRequest.getFilters() != null ? filterRequest.getFilters() : new AmlScreeningDTO();
        filter.setPartyId(partyId);
        filterRequest.setFilters(filter);

        return amlScreeningService.findAll(filterRequest)
                .map(ResponseEntity::ok);
    }

    @PostMapping
    @Operation(
            summary = "Create AML screening",
            description = "Creates a new AML screening for the specified party",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully created AML screening",
                            content = @Content(schema = @Schema(implementation = AmlScreeningDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<AmlScreeningDTO>> createAmlScreening(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "AML screening data", required = true)
            @RequestBody AmlScreeningDTO amlScreeningDTO
    ) {
        amlScreeningDTO.setPartyId(partyId);
        return amlScreeningService.create(amlScreeningDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @GetMapping("/{screeningId}")
    @Operation(
            summary = "Get screening details",
            description = "Retrieves a specific AML screening by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved AML screening",
                            content = @Content(schema = @Schema(implementation = AmlScreeningDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "AML screening not found"
                    )
            }
    )
    public Mono<ResponseEntity<AmlScreeningDTO>> getAmlScreening(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the screening", required = true)
            @PathVariable UUID screeningId
    ) {
        return amlScreeningService.getById(screeningId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{screeningId}")
    @Operation(
            summary = "Update AML screening",
            description = "Updates an existing AML screening",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated AML screening",
                            content = @Content(schema = @Schema(implementation = AmlScreeningDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "AML screening not found"
                    )
            }
    )
    public Mono<ResponseEntity<AmlScreeningDTO>> updateAmlScreening(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the screening", required = true)
            @PathVariable UUID screeningId,
            @Parameter(description = "Updated AML screening data", required = true)
            @RequestBody AmlScreeningDTO amlScreeningDTO
    ) {
        amlScreeningDTO.setPartyId(partyId);
        return amlScreeningService.update(screeningId, amlScreeningDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{screeningId}")
    @Operation(
            summary = "Delete AML screening",
            description = "Deletes an AML screening",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted AML screening"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "AML screening not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deleteAmlScreening(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the screening", required = true)
            @PathVariable UUID screeningId
    ) {
        return amlScreeningService.delete(screeningId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
