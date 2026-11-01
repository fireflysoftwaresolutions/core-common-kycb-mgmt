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
import io.fireflysoftware.core.kycb.core.services.document.v1.CorporateDocumentService;
import io.fireflysoftware.core.kycb.interfaces.dtos.document.v1.CorporateDocumentDTO;
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
@RequestMapping("/api/v1/documents/corporate")
@Tag(name = "Corporate Documents", description = "API for managing corporate documents")
public class CorporateDocumentController {

    @Autowired
    private CorporateDocumentService corporateDocumentService;

    @GetMapping
    @Operation(
            summary = "List corporate documents",
            description = "Retrieves all corporate documents with filtering capabilities",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved corporate documents",
                            content = @Content(schema = @Schema(implementation = PaginationResponse.class))
                    )
            }
    )
    public Mono<ResponseEntity<PaginationResponse<CorporateDocumentDTO>>> listCorporateDocuments(
            @Parameter(description = "Filter criteria")
            @ModelAttribute FilterRequest<CorporateDocumentDTO> filterRequest
    ) {
        return corporateDocumentService.findAll(filterRequest)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{documentId}")
    @Operation(
            summary = "Get specific corporate document",
            description = "Retrieves a specific corporate document by its ID",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved corporate document",
                            content = @Content(schema = @Schema(implementation = CorporateDocumentDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Corporate document not found"
                    )
            }
    )
    public Mono<ResponseEntity<CorporateDocumentDTO>> getCorporateDocument(
            @Parameter(description = "ID of the document", required = true)
            @PathVariable UUID documentId
    ) {
        return corporateDocumentService.getById(documentId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Add corporate document",
            description = "Adds a new corporate document",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Successfully added corporate document",
                            content = @Content(schema = @Schema(implementation = CorporateDocumentDTO.class))
                    )
            }
    )
    public Mono<ResponseEntity<CorporateDocumentDTO>> addCorporateDocument(
            @Parameter(description = "Corporate document data", required = true)
            @RequestBody CorporateDocumentDTO corporateDocumentDTO
    ) {
        return corporateDocumentService.create(corporateDocumentDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @PatchMapping("/{documentId}")
    @Operation(
            summary = "Update corporate document",
            description = "Updates an existing corporate document",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully updated corporate document",
                            content = @Content(schema = @Schema(implementation = CorporateDocumentDTO.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Corporate document not found"
                    )
            }
    )
    public Mono<ResponseEntity<CorporateDocumentDTO>> updateCorporateDocument(
            @Parameter(description = "ID of the document", required = true)
            @PathVariable UUID documentId,
            @Parameter(description = "Updated corporate document data", required = true)
            @RequestBody CorporateDocumentDTO corporateDocumentDTO
    ) {
        return corporateDocumentService.update(documentId, corporateDocumentDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{documentId}")
    @Operation(
            summary = "Delete corporate document",
            description = "Deletes a corporate document",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Successfully deleted corporate document"
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Corporate document not found"
                    )
            }
    )
    public Mono<ResponseEntity<Void>> deleteCorporateDocument(
            @Parameter(description = "ID of the document", required = true)
            @PathVariable UUID documentId
    ) {
        return corporateDocumentService.delete(documentId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
