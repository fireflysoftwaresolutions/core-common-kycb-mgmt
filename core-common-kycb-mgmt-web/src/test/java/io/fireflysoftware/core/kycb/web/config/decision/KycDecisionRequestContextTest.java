package io.fireflysoftware.core.kycb.web.config.decision;

import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.core.kycb.core.services.decision.party.KycCallerToken;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class KycDecisionRequestContextTest {
    static final String PATH="/api/v1/tenants/tenant/parties/party/kyc-cases/case/manual-decisions/actions/action";
    @Test void bearerIsEphemeralContextOnly(){
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get(PATH).header("Authorization","Bearer signed-original"));var called=new AtomicInteger();
        new KycDecisionRequestContextConfiguration().kycDecisionCallerContext().filter(exchange,e->Mono.deferContextual(context->{called.incrementAndGet();assertEquals("signed-original",context.get(KycCallerToken.class).value());assertFalse(context.get(KycCallerToken.class).toString().contains("signed-original"));return Mono.empty();})).block();assertEquals(1,called.get());assertEquals("no-store",exchange.getResponse().getHeaders().getCacheControl());
    }
    @Test void missingDuplicateAndMalformedBearerNeverReachController(){
        for(var headers:new String[][]{{},{"Bearer a","Bearer b"},{"Basic value"},{"Bearer a b"}}){var request=MockServerHttpRequest.get(PATH).header("Authorization",headers);var exchange=MockServerWebExchange.from(request);var called=new AtomicInteger();new KycDecisionRequestContextConfiguration().kycDecisionCallerContext().filter(exchange,e->{called.incrementAndGet();return Mono.empty();}).block();assertEquals(401,exchange.getResponse().getStatusCode().value());assertEquals(0,called.get());}
    }
    @Test void unrelatedRoutesAreNotChanged(){var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/kyc-verifications"));var called=new AtomicInteger();new KycDecisionRequestContextConfiguration().kycDecisionCallerContext().filter(exchange,e->{called.incrementAndGet();return Mono.empty();}).block();assertEquals(1,called.get());assertNull(exchange.getResponse().getStatusCode());}
}
