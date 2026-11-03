package io.fireflysoftware.core.kycb.web.controllers.decision;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.fireflysoftware.core.kycb.core.services.decision.*;
import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class ManualKycDecisionControllerTest {
    final Scope scope=new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
    final KycDecisionService service=mock(KycDecisionService.class);
    ManualKycDecisionController controller(){var beans=new DefaultListableBeanFactory();beans.registerSingleton("service",service);return new ManualKycDecisionController(beans.getBeanProvider(KycDecisionService.class));}
    @Test void submissionForwardsOriginalScopeActionAndEvidence(){
        var now=Instant.parse("2026-11-01T10:00:00Z");var request=new SubmitRequest(UUID.randomUUID(),-1,List.of(new Evidence(UUID.randomUUID(),"owner","original-document","1".repeat(64),now,now.plusSeconds(600))),now.plusSeconds(300));
        var receipt=new ActionReceipt(scope,request.actionId(),UUID.randomUUID(),UUID.randomUUID(),request.actionId(),"2".repeat(64),CommandKind.SUBMIT,State.SUBMITTED,0,now);
        when(service.submit(scope,request)).thenReturn(Mono.just(receipt));var response=controller().submit(scope.tenantId(),scope.partyId(),scope.kycCaseId(),request).block();
        assertSame(receipt,response.getBody());assertEquals("no-store",response.getHeaders().getCacheControl());verify(service).submit(scope,request);verifyNoMoreInteractions(service);
    }
    @Test void readRecoveryPreservesOriginalActionAndDoesNotSubmit(){
        var action=UUID.randomUUID();when(service.recover(scope,action)).thenReturn(Mono.empty());controller().recover(scope.tenantId(),scope.partyId(),scope.kycCaseId(),action).block();verify(service).recover(scope,action);verifyNoMoreInteractions(service);
    }
    @Test void unavailableCapabilityNeverReturnsAnApproval(){
        var beans=new DefaultListableBeanFactory();var controller=new ManualKycDecisionController(beans.getBeanProvider(KycDecisionService.class));
        var failed=assertThrows(ResponseStatusException.class,()->controller.current(scope.tenantId(),scope.partyId(),scope.kycCaseId(),UUID.randomUUID()).block());assertEquals(503,failed.getStatusCode().value());
    }
    @Test void unknownWithheldAndUnavailableKeepOriginalActionAnd503(){
        var action=UUID.randomUUID();for(var reason:List.of(KycDecisionException.Reason.UNKNOWN,KycDecisionException.Reason.WITHHELD,KycDecisionException.Reason.UNAVAILABLE)){
            var response=controller().failure(new KycDecisionException(action,reason,null,null));assertEquals(HttpStatus.SERVICE_UNAVAILABLE,response.getStatusCode());assertEquals(action,response.getBody().actionId());assertEquals(reason,response.getBody().reason());assertEquals("no-store",response.getHeaders().getCacheControl());
        }
    }
    @Test void explicitRefusalConflictAndMissingRemainDistinct(){
        for(var row:Map.of(KycDecisionException.Reason.REFUSED,403,KycDecisionException.Reason.CONFLICT,409,KycDecisionException.Reason.NOT_FOUND,404).entrySet())assertEquals(row.getValue(),controller().failure(new KycDecisionException(UUID.randomUUID(),row.getKey(),null,null)).getStatusCode().value());
    }
}
