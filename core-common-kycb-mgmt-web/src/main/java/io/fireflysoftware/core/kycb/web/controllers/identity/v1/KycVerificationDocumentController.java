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
import io.fireflysoftware.core.kycb.core.services.document.v1.VerificationDocumentService;
import io.fireflysoftware.core.kycb.interfaces.dtos.document.v1.VerificationDocumentDTO;
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
@RequestMapping("/api/v1/identity/parties/{partyId}/kyc/{verificationId}/documents")
@Tag(name = "KYC Verification Documents", description = "API for managing KYC verification documents")
public class KycVerificationDocumentController {

    @Autowired
    private VerificationDocumentService verificationDocumentService;

    @GetMapping
    @Operation(
            summary = "List verification documents",
            description = "Retrieves all verification documents with filtering capabilities",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved verification documents",
                            content = @Content(schema = @Schema(implementation = PaginationResponse.class))
                    )
            }
    )
    public Mono<ResponseEntity<PaginationResponse<VerificationDocumentDTO>>> listVerificationDocuments(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the verification", required = true)
            @PathVariable UUID verificationId,
            @Parameter(description = "Filter criteria")
            @ModelAttribute FilterRequest<VerificationDocumentDTO> filterRequest
    ) {
        // Set verification ID filter
        VerificationDocumentDTO filter = filterRequest.getFilters() != null ? filterRequest.getFilters() : new VerificationDocumentDTO();
        filter.setKycVerificationId(verificationId);
        filterRequest.setFilters(filter);

        return verificationDocumentService.findAll(filterRequest)
                .map(ResponseEntity::ok);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Add verification document",
            description = "Adds a new verification document to the specified KYC verification",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully added verification document",
                            content = @Content(schema = @Schema(implementation = VerificationDocumentDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<VerificationDocumentDTO>> addVerificationDocument(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the verification", required = true)
            @PathVariable UUID verificationId,
            @Parameter(description = "Verification document data", required = true)
            @RequestBody VerificationDocumentDTO verificationDocumentDTO
    ) {
        verificationDocumentDTO.setKycVerificationId(verificationId);
        return verificationDocumentService.create(verificationDocumentDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @GetMapping("/{documentId}")
    @Operation(
            summary = "Get specific verification document",
            description = "Retrieves a specific verification document by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved verification document",
                            content = @Content(schema = @Schema(implementation = VerificationDocumentDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Verification document not found"
                    )
            }
    )
    public Mono<ResponseEntity<VerificationDocumentDTO>> getVerificationDocument(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the verification", required = true)
            @PathVariable UUID verificationId,
            @Parameter(description = "ID of the document", required = true)
            @PathVariable UUID documentId
    ) {
        return verificationDocumentService.getById(documentId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{documentId}")
    @Operation(
            summary = "Update verification document",
            description = "Updates an existing verification document",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated verification document",
                            content = @Content(schema = @Schema(implementation = VerificationDocumentDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Verification document not found"
                    )
            }
    )
    public Mono<ResponseEntity<VerificationDocumentDTO>> updateVerificationDocument(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the verification", required = true)
            @PathVariable UUID verificationId,
            @Parameter(description = "ID of the document", required = true)
            @PathVariable UUID documentId,
            @Parameter(description = "Updated verification document data", required = true)
            @RequestBody VerificationDocumentDTO verificationDocumentDTO
    ) {
        verificationDocumentDTO.setKycVerificationId(verificationId);
        return verificationDocumentService.update(documentId, verificationDocumentDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{documentId}")
    @Operation(
            summary = "Delete verification document",
            description = "Deletes a verification document",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted verification document"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Verification document not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deleteVerificationDocument(
            @Parameter(description = "ID of the party", required = true)
            @PathVariable UUID partyId,
            @Parameter(description = "ID of the verification", required = true)
            @PathVariable UUID verificationId,
            @Parameter(description = "ID of the document", required = true)
            @PathVariable UUID documentId
    ) {
        return verificationDocumentService.delete(documentId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
