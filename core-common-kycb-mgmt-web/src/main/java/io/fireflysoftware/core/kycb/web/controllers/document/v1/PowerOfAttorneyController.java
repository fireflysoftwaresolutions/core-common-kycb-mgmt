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


package io.fireflysoftware.core.kycb.web.controllers.document.v1;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.kycb.core.services.power.v1.PowerOfAttorneyService;
import io.fireflysoftware.core.kycb.interfaces.dtos.power.v1.PowerOfAttorneyDTO;
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
@RequestMapping("/api/v1/documents/powers-of-attorney")
@Tag(name = "Powers of Attorney", description = "API for managing powers of attorney")
public class PowerOfAttorneyController {

    @Autowired
    private PowerOfAttorneyService powerOfAttorneyService;

    @GetMapping
    @Operation(
            summary = "List powers of attorney",
            description = "Retrieves all powers of attorney with filtering capabilities",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved powers of attorney",
                            content = @Content(schema = @Schema(implementation = PaginationResponse.class))
                    )
            }
    )
    public Mono<ResponseEntity<PaginationResponse<PowerOfAttorneyDTO>>> listPowersOfAttorney(
            @Parameter(description = "Filter criteria")
            @ModelAttribute FilterRequest<PowerOfAttorneyDTO> filterRequest
    ) {
        return powerOfAttorneyService.findAll(filterRequest)
                .map(ResponseEntity::ok);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Add power of attorney",
            description = "Creates a new power of attorney",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully created power of attorney",
                            content = @Content(schema = @Schema(implementation = PowerOfAttorneyDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<PowerOfAttorneyDTO>> addPowerOfAttorney(
            @Parameter(description = "Power of attorney data", required = true)
            @RequestBody PowerOfAttorneyDTO powerOfAttorneyDTO
    ) {
        return powerOfAttorneyService.create(powerOfAttorneyDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @GetMapping("/{powerId}")
    @Operation(
            summary = "Get specific power of attorney",
            description = "Retrieves a specific power of attorney by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved power of attorney",
                            content = @Content(schema = @Schema(implementation = PowerOfAttorneyDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Power of attorney not found"
                    )
            }
    )
    public Mono<ResponseEntity<PowerOfAttorneyDTO>> getPowerOfAttorney(
            @Parameter(description = "ID of the power of attorney", required = true)
            @PathVariable UUID powerId
    ) {
        return powerOfAttorneyService.getById(powerId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{powerId}")
    @Operation(
            summary = "Update power of attorney",
            description = "Updates an existing power of attorney",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated power of attorney",
                            content = @Content(schema = @Schema(implementation = PowerOfAttorneyDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Power of attorney not found"
                    )
            }
    )
    public Mono<ResponseEntity<PowerOfAttorneyDTO>> updatePowerOfAttorney(
            @Parameter(description = "ID of the power of attorney", required = true)
            @PathVariable UUID powerId,
            @Parameter(description = "Updated power of attorney data", required = true)
            @RequestBody PowerOfAttorneyDTO powerOfAttorneyDTO
    ) {
        return powerOfAttorneyService.update(powerId, powerOfAttorneyDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{powerId}")
    @Operation(
            summary = "Delete power of attorney",
            description = "Deletes a power of attorney",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted power of attorney"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Power of attorney not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deletePowerOfAttorney(
            @Parameter(description = "ID of the power of attorney", required = true)
            @PathVariable UUID powerId
    ) {
        return powerOfAttorneyService.delete(powerId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
