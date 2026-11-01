package io.fireflysoftware.core.kycb.web.controllers.reproofing.impl;
import io.fireflysoftware.core.kycb.core.reproofing.ReproofingService;
import io.fireflysoftware.core.kycb.interfaces.reproofing.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/identity-recovery/cases")
@Tag(name="Identity Reproofing")
public class ReproofingControllerImpl {
    private final ObjectProvider<ReproofingService> services;
    public ReproofingControllerImpl(ObjectProvider<ReproofingService> services){this.services=services;}
    private ReproofingService service(){var value=services.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Recovery unavailable");return value;}
    @PostMapping("")
    @Operation(operationId="createReproofingCase")
    public Mono<ReproofingCaseResponse> createReproofingCase(@RequestBody CreateReproofingCaseRequest request){
        return Mono.defer(()->service().create(request));
    }
    @PostMapping("/{caseId}/inspect")
    @Operation(operationId="inspectReproofingCase")
    public Mono<ReproofingCaseResponse> inspectReproofingCase(@PathVariable UUID caseId, @RequestBody ConfirmReproofingRequest request){
        return Mono.defer(()->service().inspect(caseId,request));
    }
    @PostMapping("/{caseId}/provider-session")
    @Operation(operationId="bindReproofingSession")
    public Mono<ReproofingCaseResponse> bindReproofingSession(@PathVariable UUID caseId, @RequestBody BindReproofingSessionRequest request){
        return Mono.defer(()->service().bindSession(caseId,request));
    }
    @PostMapping("/{caseId}/confirm")
    @Operation(operationId="confirmReproofingCase")
    public Mono<ReproofingCaseResponse> confirmReproofingCase(@PathVariable UUID caseId, @RequestBody ConfirmReproofingRequest request){
        return Mono.defer(()->service().confirm(caseId,request));
    }
    @PostMapping("/{caseId}/authorizations/consume")
    @Operation(operationId="consumeReproofingAuthorization")
    public Mono<ReproofingAuthorization> consumeReproofingAuthorization(@PathVariable UUID caseId, @RequestBody ReproofingAuthorizationRequest request){
        return Mono.defer(()->service().consume(caseId,request));
    }
    @PostMapping("/{caseId}/authorizations/verify")
    @Operation(operationId="verifyReproofingAuthorization")
    public Mono<ReproofingAuthorization> verifyReproofingAuthorization(@PathVariable UUID caseId, @RequestBody ReproofingAuthorizationRequest request){
        return Mono.defer(()->service().verify(caseId,request));
    }
    @PostMapping("/{caseId}/executions")
    @Operation(operationId="recordReproofingExecution")
    public Mono<ReproofingCaseResponse> recordReproofingExecution(@PathVariable UUID caseId, @RequestBody RecordReproofingExecutionRequest request){
        return Mono.defer(()->service().record(caseId,request));
    }
}
