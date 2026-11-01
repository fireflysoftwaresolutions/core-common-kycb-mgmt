package io.fireflysoftware.core.kycb.web.reproofing;
import io.fireflysoftware.core.kycb.web.reproofing.impl.ReproofingBodyLimitFilterImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
class ReproofingBodyLimitTest {
 @Test void unendingRecoveryBodyTimesOutWithoutDispatch(){
  var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/identity-recovery/cases").body(Flux.never()));
  StepVerifier.withVirtualTime(()->new ReproofingBodyLimitFilterImpl().filter(exchange,e->Mono.error(new AssertionError("Unending body must not dispatch"))))
   .thenAwait(Duration.ofSeconds(6)).expectComplete().verify(Duration.ofSeconds(1));
  assertEquals(HttpStatus.REQUEST_TIMEOUT,exchange.getResponse().getStatusCode());
 }
 @Test void oversizedRecoveryBodyRefusesBeforeDispatch(){
  var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/identity-recovery/cases").body("x".repeat(32769)));
  new ReproofingBodyLimitFilterImpl().filter(exchange,e->Mono.error(new AssertionError("Oversized body must not dispatch"))).block();
  assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,exchange.getResponse().getStatusCode());
 }
}
