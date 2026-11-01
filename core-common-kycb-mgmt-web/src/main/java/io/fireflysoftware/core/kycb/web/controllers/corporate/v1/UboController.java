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
import io.fireflysoftware.core.kycb.core.services.ownership.v1.UboService;
import io.fireflysoftware.core.kycb.interfaces.dtos.ownership.v1.UboDTO;
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
@RequestMapping("/api/v1/corporate/parties/{partyId}/ubos")
@Tag(name = "UBO Management", description = "API for managing Ultimate Beneficial Owners")
public class UboController {

    @Autowired
    private UboService uboService;

    @GetMapping
    @Operation(
            summary = "List UBOs",
            description = "Retrieves all UBOs for the specified party",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved UBOs",
                            content = @Content(schema = @Schema(implementation = PaginationResponse.class))
                    )
            }
    )
    public Mono<ResponseEntity<PaginationResponse<UboDTO>>> listUbos(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "Filter criteria")
            @ModelAttribute FilterRequest<UboDTO> filterRequest
    ) {
        // Set party ID filter
        UboDTO filter = filterRequest.getFilters() != null ? filterRequest.getFilters() : new UboDTO();
        filter.setPartyId(partyId);
        filterRequest.setFilters(filter);

        return uboService.findAll(filterRequest)
                .map(ResponseEntity::ok);
    }

    @PostMapping
    @Operation(
            summary = "Add UBO",
            description = "Adds a new UBO to the specified party",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully added UBO",
                            content = @Content(schema = @Schema(implementation = UboDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<UboDTO>> addUbo(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "UBO data", required = true)
            @RequestBody UboDTO uboDTO
    ) {
        uboDTO.setPartyId(partyId);
        return uboService.create(uboDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @GetMapping("/{uboId}")
    @Operation(
            summary = "Get specific UBO",
            description = "Retrieves a specific UBO by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved UBO",
                            content = @Content(schema = @Schema(implementation = UboDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "UBO not found"
                    )
            }
    )
    public Mono<ResponseEntity<UboDTO>> getUbo(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the UBO", required = true)
            @PathVariable UUID uboId
    ) {
        return uboService.getById(uboId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{uboId}")
    @Operation(
            summary = "Update UBO details",
            description = "Updates an existing UBO",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated UBO",
                            content = @Content(schema = @Schema(implementation = UboDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "UBO not found"
                    )
            }
    )
    public Mono<ResponseEntity<UboDTO>> updateUbo(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the UBO", required = true)
            @PathVariable UUID uboId,
            @Parameter(description = "Updated UBO data", required = true)
            @RequestBody UboDTO uboDTO
    ) {
        uboDTO.setPartyId(partyId);
        return uboService.update(uboId, uboDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{uboId}")
    @Operation(
            summary = "Delete UBO",
            description = "Deletes a UBO",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted UBO"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "UBO not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deleteUbo(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the UBO", required = true)
            @PathVariable UUID uboId
    ) {
        return uboService.delete(uboId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
