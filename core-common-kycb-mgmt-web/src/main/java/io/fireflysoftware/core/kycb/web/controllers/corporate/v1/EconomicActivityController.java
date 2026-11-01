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
import io.fireflysoftware.core.kycb.core.services.economic.v1.EconomicActivityService;
import io.fireflysoftware.core.kycb.interfaces.dtos.economic.v1.EconomicActivityDTO;
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
@RequestMapping("/api/v1/corporate/parties/{partyId}/activities")
@Tag(name = "Economic Activities", description = "API for managing economic activities of legal persons")
public class EconomicActivityController {

    @Autowired
    private EconomicActivityService economicActivityService;

    @GetMapping
    @Operation(
            summary = "List economic activities",
            description = "Retrieves all economic activities for the specified legal person with filtering capabilities",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved economic activities",
                            content = @Content(schema = @Schema(implementation = PaginationResponse.class))
                    )
            }
    )
    public Mono<ResponseEntity<PaginationResponse<EconomicActivityDTO>>> listEconomicActivities(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "Filter criteria")
            @ModelAttribute FilterRequest<EconomicActivityDTO> filterRequest
    ) {
        // Set party ID filter
        EconomicActivityDTO filter = filterRequest.getFilters() != null ? filterRequest.getFilters() : new EconomicActivityDTO();
        filter.setPartyId(partyId);
        filterRequest.setFilters(filter);

        return economicActivityService.findAll(filterRequest)
                .map(ResponseEntity::ok);
    }

    @PostMapping
    @Operation(
            summary = "Add economic activity",
            description = "Adds a new economic activity to the specified legal person",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully added economic activity",
                            content = @Content(schema = @Schema(implementation = EconomicActivityDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<EconomicActivityDTO>> addEconomicActivity(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "Economic activity data", required = true)
            @RequestBody EconomicActivityDTO economicActivityDTO
    ) {
        economicActivityDTO.setPartyId(partyId);
        return economicActivityService.create(economicActivityDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @GetMapping("/{activityId}")
    @Operation(
            summary = "Get specific activity",
            description = "Retrieves a specific economic activity by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved economic activity",
                            content = @Content(schema = @Schema(implementation = EconomicActivityDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Economic activity not found"
                    )
            }
    )
    public Mono<ResponseEntity<EconomicActivityDTO>> getEconomicActivity(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the activity", required = true)
            @PathVariable UUID activityId
    ) {
        return economicActivityService.getById(activityId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{activityId}")
    @Operation(
            summary = "Update economic activity",
            description = "Updates an existing economic activity",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated economic activity",
                            content = @Content(schema = @Schema(implementation = EconomicActivityDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Economic activity not found"
                    )
            }
    )
    public Mono<ResponseEntity<EconomicActivityDTO>> updateEconomicActivity(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the activity", required = true)
            @PathVariable UUID activityId,
            @Parameter(description = "Updated economic activity data", required = true)
            @RequestBody EconomicActivityDTO economicActivityDTO
    ) {
        economicActivityDTO.setPartyId(partyId);
        return economicActivityService.update(activityId, economicActivityDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{activityId}")
    @Operation(
            summary = "Delete economic activity",
            description = "Deletes an economic activity",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted economic activity"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Economic activity not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deleteEconomicActivity(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the activity", required = true)
            @PathVariable UUID activityId
    ) {
        return economicActivityService.delete(activityId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
