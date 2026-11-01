package io.fireflysoftware.core.kycb.core.reproofing;

import io.fireflysoftware.core.kycb.interfaces.reproofing.ReproofingNotificationQueue;
import reactor.core.publisher.Mono;

public interface ReproofingNotificationPort {
    Mono<Integer> dispatchOne();
    Mono<ReproofingNotificationQueue> status();
}
