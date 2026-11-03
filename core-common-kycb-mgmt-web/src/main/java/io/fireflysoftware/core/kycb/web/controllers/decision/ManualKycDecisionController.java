/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.web.controllers.decision;

import io.fireflysoftware.core.kycb.core.services.decision.*;
import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(value = "/api/v1/tenants/{tenantId}/parties/{partyId}/kyc-cases/{caseId}/manual-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "ManualCardEnrollmentKyc", description = "Explicit staff manual KYC evidence review for card enrollment; no provider verification or issuance")
@SecurityRequirement(name = "manualKycBearer")
@SecurityScheme(name = "manualKycBearer", type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class ManualKycDecisionController {
    private final ObjectProvider<KycDecisionService> services;
    public ManualKycDecisionController(ObjectProvider<KycDecisionService> services) { this.services = services; }

    @PostMapping(value = "/submissions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @DisableIdempotency
    @Operation(operationId = "submitManualCardEnrollmentKyc", summary = "Submit manual evidence under the original staff action")
    public Mono<ResponseEntity<ActionReceipt>> submit(@PathVariable UUID tenantId, @PathVariable UUID partyId,
            @PathVariable UUID caseId, @RequestBody SubmitRequest request) {
        return Mono.defer(() -> service().submit(new Scope(tenantId, partyId, caseId), request)).map(this::response);
    }

    @PostMapping(value = "/reviews", consumes = MediaType.APPLICATION_JSON_VALUE)
    @DisableIdempotency
    @Operation(operationId = "reviewManualCardEnrollmentKyc", summary = "Independently review the original evidence and digest")
    public Mono<ResponseEntity<ActionReceipt>> review(@PathVariable UUID tenantId, @PathVariable UUID partyId,
            @PathVariable UUID caseId, @RequestBody ReviewRequest request) {
        return Mono.defer(() -> service().review(new Scope(tenantId, partyId, caseId), request)).map(this::response);
    }

    @PostMapping(value = "/revocations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @DisableIdempotency
    @Operation(operationId = "revokeManualCardEnrollmentKyc", summary = "Revoke the selected original decision under current staff authority")
    public Mono<ResponseEntity<ActionReceipt>> revoke(@PathVariable UUID tenantId, @PathVariable UUID partyId,
            @PathVariable UUID caseId, @RequestBody RevokeRequest request) {
        return Mono.defer(() -> service().revoke(new Scope(tenantId, partyId, caseId), request)).map(this::response);
    }

    @GetMapping("/actions/{actionId}")
    @DisableIdempotency
    @Operation(operationId = "recoverManualCardEnrollmentKycAction", summary = "Read the original action receipt without renewing or changing a decision")
    public Mono<ResponseEntity<ActionReceipt>> recover(@PathVariable UUID tenantId, @PathVariable UUID partyId,
            @PathVariable UUID caseId, @PathVariable UUID actionId) {
        return Mono.defer(() -> service().recover(new Scope(tenantId, partyId, caseId), actionId)).map(this::response);
    }

    @GetMapping("/decisions/{decisionActionId}/current")
    @DisableIdempotency
    @Operation(operationId = "observeCurrentManualCardEnrollmentKyc", summary = "Observe an unrevoked current manual decision under fresh authority")
    public Mono<ResponseEntity<CurrentDecision>> current(@PathVariable UUID tenantId, @PathVariable UUID partyId,
            @PathVariable UUID caseId, @PathVariable UUID decisionActionId) {
        return Mono.defer(() -> service().current(new Scope(tenantId, partyId, caseId), decisionActionId)).map(this::response);
    }

    public record Failure(UUID actionId, KycDecisionException.Reason reason) {}
    public record InvalidRequest(String code) {}
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<InvalidRequest> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new InvalidRequest("EXPLICIT_MANUAL_KYC_REQUEST_REQUIRED"));
    }
    @ExceptionHandler(KycDecisionException.class)
    ResponseEntity<Failure> failure(KycDecisionException error) {
        HttpStatus status = switch (error.reason()) {
            case CONFLICT -> HttpStatus.CONFLICT;
            case REFUSED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAVAILABLE, UNKNOWN, WITHHELD -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Failure(error.actionId(), error.reason()));
    }
    private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private KycDecisionService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Manual KYC capability is not configured");
        return service;
    }
}
