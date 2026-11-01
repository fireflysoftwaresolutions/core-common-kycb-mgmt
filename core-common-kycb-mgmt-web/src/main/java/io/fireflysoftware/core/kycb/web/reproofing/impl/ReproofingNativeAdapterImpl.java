package io.fireflysoftware.core.kycb.web.reproofing.impl;
import io.fireflysoftware.core.kycb.core.reproofing.ReproofingNativePort;
import io.fireflysoftware.core.kycb.interfaces.reproofing.*;
import reactor.core.publisher.Mono;
public final class ReproofingNativeAdapterImpl implements ReproofingNativePort {
 private final ReproofingRemoteClientImpl client;
 public ReproofingNativeAdapterImpl(ReproofingRemoteClientImpl client){this.client=client;}
 public Mono<ReproofingAdmission> prepare(ReproofingNativeRequest r){return client.call("/prepare",r,ReproofingAdmission.class);}
 public Mono<ReproofingAdmission> admit(ReproofingNativeRequest r){return client.call("/admit",r,ReproofingAdmission.class);}
 public Mono<ReproofingExecution> journal(ReproofingNativeRequest r){return client.call("/journal",r,ReproofingExecution.class);}
}
