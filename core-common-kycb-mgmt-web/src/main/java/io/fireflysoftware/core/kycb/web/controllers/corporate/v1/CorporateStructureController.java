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


package io.fireflysoftware.core.kycb.web.controllers.corporate.v1;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.kycb.core.services.corporate.v1.CorporateStructureService;
import io.fireflysoftware.core.kycb.interfaces.dtos.corporate.v1.CorporateStructureDTO;
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
@RequestMapping("/api/v1/corporate/parties/{partyId}/structure")
@Tag(name = "Corporate Structure", description = "API for managing corporate structure relationships")
public class CorporateStructureController {

    @Autowired
    private CorporateStructureService corporateStructureService;

    @GetMapping
    @Operation(
            summary = "List structure relationships",
            description = "Retrieves all corporate structure relationships with filtering capabilities",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved corporate structure relationships",
                            content = @Content(schema = @Schema(implementation = PaginationResponse.class))
                    )
            }
    )
    public Mono<ResponseEntity<PaginationResponse<CorporateStructureDTO>>> listStructureRelationships(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "Filter criteria")
            @ModelAttribute FilterRequest<CorporateStructureDTO> filterRequest
    ) {
        // Set party ID filter
        CorporateStructureDTO filter = filterRequest.getFilters() != null ? filterRequest.getFilters() : new CorporateStructureDTO();
        filter.setPartyId(partyId);
        filterRequest.setFilters(filter);

        return corporateStructureService.findAll(filterRequest)
                .map(ResponseEntity::ok);
    }

    @PostMapping
    @Operation(
            summary = "Add structure relationship",
            description = "Adds a new structure relationship to the specified party",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully added structure relationship",
                            content = @Content(schema = @Schema(implementation = CorporateStructureDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<CorporateStructureDTO>> addStructureRelationship(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "Structure relationship data", required = true)
            @RequestBody CorporateStructureDTO corporateStructureDTO
    ) {
        corporateStructureDTO.setPartyId(partyId);
        return corporateStructureService.create(corporateStructureDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @GetMapping("/{structureId}")
    @Operation(
            summary = "Get specific structure relationship",
            description = "Retrieves a specific structure relationship by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved structure relationship",
                            content = @Content(schema = @Schema(implementation = CorporateStructureDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Structure relationship not found"
                    )
            }
    )
    public Mono<ResponseEntity<CorporateStructureDTO>> getStructureRelationship(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the structure relationship", required = true)
            @PathVariable UUID structureId
    ) {
        return corporateStructureService.getById(structureId)
                .filter(dto -> partyId.equals(dto.getPartyId()))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{structureId}")
    @Operation(
            summary = "Update structure relationship",
            description = "Updates an existing structure relationship",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated structure relationship",
                            content = @Content(schema = @Schema(implementation = CorporateStructureDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Structure relationship not found"
                    )
            }
    )
    public Mono<ResponseEntity<CorporateStructureDTO>> updateStructureRelationship(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the structure relationship", required = true)
            @PathVariable UUID structureId,
            @Parameter(description = "Updated structure relationship data", required = true)
            @RequestBody CorporateStructureDTO corporateStructureDTO
    ) {
        corporateStructureDTO.setPartyId(partyId);
        return corporateStructureService.update(structureId, corporateStructureDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{structureId}")
    @Operation(
            summary = "Delete structure relationship",
            description = "Deletes a structure relationship",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted structure relationship"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Structure relationship not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deleteStructureRelationship(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the structure relationship", required = true)
            @PathVariable UUID structureId
    ) {
        return corporateStructureService.getById(structureId)
                .filter(dto -> partyId.equals(dto.getPartyId()))
                .flatMap(dto -> corporateStructureService.delete(structureId)
                        .thenReturn(ResponseEntity.noContent().<Void>build()))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
