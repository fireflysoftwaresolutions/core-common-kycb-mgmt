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

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.kycb.core.services.kyb.v1.KybVerificationService;
import io.fireflysoftware.core.kycb.interfaces.dtos.kyb.v1.KybVerificationDTO;
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
@RequestMapping("/api/v1/identity/parties/{partyId}/kyb")
@Tag(name = "KYB Verification", description = "API for managing KYB verifications")
public class KybVerificationController {

    @Autowired
    private KybVerificationService kybVerificationService;

    @GetMapping
    @Operation(
            summary = "List KYB verifications for a party",
            description = "Retrieves all KYB verifications for the specified party ID with optional filtering",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved KYB verifications",
                            content = @Content(schema = @Schema(implementation = PaginationResponse.class))
                    )
            }
    )
    public Mono<ResponseEntity<PaginationResponse<KybVerificationDTO>>> listKybVerifications(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @ModelAttribute FilterRequest<KybVerificationDTO> filterRequest
    ) {

        // Create a filter with the party ID
        KybVerificationDTO filter = new KybVerificationDTO();
        filter.setPartyId(partyId);
        filterRequest.setFilters(filter);

        return kybVerificationService.findAll(filterRequest)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{verificationId}")
    @Operation(
            summary = "Get specific KYB verification",
            description = "Retrieves a specific KYB verification by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved KYB verification",
                            content = @Content(schema = @Schema(implementation = KybVerificationDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "KYB verification not found"
                    )
            }
    )
    public Mono<ResponseEntity<KybVerificationDTO>> getKybVerification(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the verification", required = true)
            @PathVariable UUID verificationId
    ) {
        return kybVerificationService.getById(verificationId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Start new KYB verification",
            description = "Creates a new KYB verification for the specified party",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully created KYB verification",
                            content = @Content(schema = @Schema(implementation = KybVerificationDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<KybVerificationDTO>> createKybVerification(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "KYB verification data", required = true)
            @RequestBody KybVerificationDTO kybVerificationDTO
    ) {
        kybVerificationDTO.setPartyId(partyId);
        return kybVerificationService.create(kybVerificationDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @PatchMapping("/{verificationId}")
    @Operation(
            summary = "Update KYB verification",
            description = "Updates an existing KYB verification",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated KYB verification",
                            content = @Content(schema = @Schema(implementation = KybVerificationDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "KYB verification not found"
                    )
            }
    )
    public Mono<ResponseEntity<KybVerificationDTO>> updateKybVerification(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the verification", required = true)
            @PathVariable UUID verificationId,
            @Parameter(description = "Updated KYB verification data", required = true)
            @RequestBody KybVerificationDTO kybVerificationDTO
    ) {
        kybVerificationDTO.setPartyId(partyId);
        return kybVerificationService.update(verificationId, kybVerificationDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{verificationId}")
    @Operation(
            summary = "Delete KYB verification",
            description = "Deletes a KYB verification",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted KYB verification"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "KYB verification not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deleteKybVerification(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the verification", required = true)
            @PathVariable UUID verificationId
    ) {
        return kybVerificationService.delete(verificationId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
