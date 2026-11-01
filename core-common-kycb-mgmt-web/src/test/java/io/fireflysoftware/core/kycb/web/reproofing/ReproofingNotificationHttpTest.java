package io.fireflysoftware.core.kycb.web.reproofing;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class ReproofingNotificationHttpTest {
    @TempDir Path temp;
    void openssl(String... args) throws Exception {
        var command = new ArrayList<String>(); command.add("openssl"); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).directory(temp.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS)); assertEquals(0,process.exitValue());
    }
    void probe(String base,String mode) throws Exception {
        var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-Djavax.net.ssl.trustStore="+temp.resolve("trust.p12"), "-Djavax.net.ssl.trustStorePassword=fixture",
            "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
            ReproofingNotificationHttpProbeImpl.class.getName(),base,mode);
        var output=temp.resolve("probe-output");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertTrue(process.waitFor(15,TimeUnit.SECONDS));
        assertEquals(0,process.exitValue(),()->{try{return Files.readString(output);}catch(Exception e){return "Probe unavailable";}});
    }
    String valid() {return "{\"observedAt\":\""+Instant.now()+"\",\"pending\":0,\"sending\":0,\"retrying\":0,\"delivered\":2,\"failed\":0,\"invalid\":0,\"due\":0,\"oldestDueDelaySeconds\":0}";}
    @Test void genuineTlsWireUsesLimitOneAndRejectsRedirectsOversizeMalformedAndSlowResponses() throws Exception {
        openssl("req","-x509","-newkey","rsa:2048","-nodes","-keyout","key.pem","-out","cert.pem","-days","1","-subj","/CN=localhost","-addext","subjectAltName=DNS:localhost");
        openssl("pkcs12","-export","-out","server.p12","-inkey","key.pem","-in","cert.pem","-passout","pass:fixture");
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(temp.resolve("server.p12"))){keys.load(input,"fixture".toCharArray());}
        var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,"fixture".toCharArray());
        var ssl=SSLContext.getInstance("TLS");ssl.init(managers.getKeyManagers(),null,null);
        var trust=KeyStore.getInstance("PKCS12");trust.load(null,"fixture".toCharArray());
        try(var input=Files.newInputStream(temp.resolve("cert.pem"))){trust.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(input));}
        try(var output=Files.newOutputStream(temp.resolve("trust.p12"))){trust.store(output,"fixture".toCharArray());}
        var server=HttpsServer.create(new java.net.InetSocketAddress("localhost",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        var response=new AtomicReference<>(valid());var code=new AtomicInteger(200);var delay=new AtomicLong();
        var invalidRequest=new AtomicBoolean();var redirected=new AtomicInteger();var calls=new AtomicInteger();
        server.createContext("/realms/test/firefly-recovery/lost-credentials/rpc",exchange->{
            try {
                calls.incrementAndGet();String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                String basic="Basic "+Base64.getEncoder().encodeToString(("notification:notification-fixture-"+"x".repeat(40)).getBytes(StandardCharsets.UTF_8));
                boolean dispatch=exchange.getRequestURI().getPath().endsWith("/dispatch");
                if(!exchange.getRequestMethod().equals("POST") || !basic.equals(exchange.getRequestHeaders().getFirst("Authorization")) || !(dispatch?body.equals("{\"limit\":1}"):body.equals("{}"))) invalidRequest.set(true);
                if(delay.get()>0) Thread.sleep(delay.get());
                byte[] payload=(dispatch?"{\"processed\":1}":response.get()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.getResponseHeaders().set("Location","https://localhost:"+server.getAddress().getPort()+"/redirected");
                exchange.sendResponseHeaders(code.get(),payload.length);exchange.getResponseBody().write(payload);
            } catch(Exception ignored) {} finally {exchange.close();}
        });
        server.createContext("/redirected",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});
        server.start();
        try {
            String base="https://localhost:"+server.getAddress().getPort()+"/realms/test/firefly-recovery/lost-credentials/rpc";
            probe(base,"success"); assertFalse(invalidRequest.get()); assertEquals(2,calls.get());
            for(String malformed:new String[]{valid().replace("\"pending\":0","\"pending\":-1"),valid().replace("\"pending\":0","\"pending\":0,\"private-provider-detail\":1"),valid().replace("\"pending\":0","\"pending\":0,\"pending\":0"),valid()+" {}",valid().replace("\"pending\":0","\"pending\":null"),"private-provider-detail".repeat(2000)}) {
                response.set(malformed);probe(base,"reject");
            }
            response.set(valid());code.set(302);probe(base,"reject");assertEquals(0,redirected.get());
            code.set(503);response.set("private-provider-detail");probe(base,"reject");
            code.set(200);response.set(valid());delay.set(1500);probe(base,"reject");
        } finally {server.stop(0);}
    }
}
