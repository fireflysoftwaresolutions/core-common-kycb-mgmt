package io.fireflysoftware.core.kycb.sdk;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.fireflysoftware.core.kycb.sdk.api.ManualCardEnrollmentKycApi;
import io.fireflysoftware.core.kycb.sdk.invoker.ApiClient;
import io.fireflysoftware.core.kycb.sdk.invoker.auth.HttpBearerAuth;
import io.fireflysoftware.core.kycb.sdk.model.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ManualKycGeneratedContractTest {
    @Test void packagedCompatibilityAliasContainsTheSameCompleteProducerBytes()throws Exception{
        try(var canonical=ApiClient.class.getResourceAsStream("/openapi.yml");var alias=ApiClient.class.getResourceAsStream("/api-spec/openapi.yml")){
            assertNotNull(canonical);assertNotNull(alias);byte[] original=canonical.readAllBytes();assertArrayEquals(original,alias.readAllBytes());
            var spec=new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(original);
            assertTrue(spec.path("paths").toString().contains("submitManualCardEnrollmentKyc"));assertEquals("string",spec.path("components").path("schemas").path("FireflyInstant").path("type").asText());
        }
    }
    @Test void originalProducerMapsEveryAuthoritativeTimestampToInstant()throws Exception{
        for(var row:Map.of(KycManualEvidence.class,List.of("getCollectedAt","getValidUntil"),
                KycManualSubmitRequest.class,List.of("getValidUntil"),KycManualActionReceipt.class,List.of("getRecordedAt"),
                KycManualCurrentDecision.class,List.of("getBusinessValidUntil","getObservedAt","getEffectiveUntil")).entrySet())
            for(var method:row.getValue())assertEquals(Instant.class,row.getKey().getMethod(method).getReturnType());
        for(var type:List.of(KycManualSubmitRequest.class,KycManualReviewRequest.class,KycManualRevokeRequest.class))
            assertTrue(Arrays.stream(type.getDeclaredFields()).noneMatch(field->field.getName().toLowerCase(Locale.ROOT).contains("actor")||field.getName().toLowerCase(Locale.ROOT).contains("bearer")));
    }
    @Test void actualSdkRecoveryUsesNamedBearerOriginalActionAndTypedReceipt()throws Exception{
        try(var f=new Fixture("2026-11-01T10:00:00.123456Z")){
            var response=f.api.recoverManualCardEnrollmentKycActionWithHttpInfo(f.tenant,f.party,f.kyc,f.action).block(Duration.ofSeconds(5));
            assertEquals(200,response.getStatusCode().value());assertEquals(f.action,response.getBody().getActionId());assertEquals(f.action,response.getBody().getSelectionActionId());assertEquals(f.receipt,response.getBody().getReceiptId());
            assertEquals(Instant.parse("2026-11-01T10:00:00.123456Z"),response.getBody().getRecordedAt());assertEquals(f.tenant,response.getBody().getScope().getTenantId());assertEquals(1,f.calls.get());assertNull(f.failure.get());
        }
    }
    @Test void actualSdkRejectsOffsetlessAuthoritativeReceiptTime()throws Exception{
        try(var f=new Fixture("2026-11-01T10:00:00.123456")){
            assertThrows(RuntimeException.class,()->f.api.recoverManualCardEnrollmentKycActionWithHttpInfo(f.tenant,f.party,f.kyc,f.action).block(Duration.ofSeconds(5)));assertEquals(1,f.calls.get());assertNull(f.failure.get());
        }
    }
    static final class Fixture implements AutoCloseable {
        final UUID tenant=UUID.randomUUID(),party=UUID.randomUUID(),kyc=UUID.randomUUID(),action=UUID.randomUUID(),receipt=UUID.randomUUID();
        final HttpServer server;final ManualCardEnrollmentKycApi api;final AtomicInteger calls=new AtomicInteger();final AtomicReference<Throwable> failure=new AtomicReference<>();
        Fixture(String time)throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
                calls.incrementAndGet();try{assertEquals("GET",exchange.getRequestMethod());assertEquals("/api/v1/tenants/"+tenant+"/parties/"+party+"/kyc-cases/"+kyc+"/manual-decisions/actions/"+action,exchange.getRequestURI().toString());assertEquals("Bearer controlled-wire-token",exchange.getRequestHeaders().getFirst("Authorization"));assertNull(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));assertEquals(0,exchange.getRequestBody().readAllBytes().length);}catch(Throwable error){failure.set(error);}
                String json="{\"scope\":{\"tenantId\":\""+tenant+"\",\"partyId\":\""+party+"\",\"kycCaseId\":\""+kyc+"\"},\"actionId\":\""+action+"\",\"receiptId\":\""+receipt+"\",\"eventId\":\""+UUID.randomUUID()+"\",\"selectionActionId\":\""+action+"\",\"submissionDigest\":\""+"1".repeat(64)+"\",\"command\":\"SUBMIT\",\"resultingState\":\"SUBMITTED\",\"resultingControlVersion\":0,\"recordedAt\":\""+time+"\"}";
                byte[] bytes=json.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();var client=new ApiClient().setBasePath("http://127.0.0.1:"+server.getAddress().getPort());assertInstanceOf(HttpBearerAuth.class,client.getAuthentication("manualKycBearer"));((HttpBearerAuth)client.getAuthentication("manualKycBearer")).setBearerToken("controlled-wire-token");api=new ManualCardEnrollmentKycApi(client);
        }
        public void close(){server.stop(0);}
    }
}
