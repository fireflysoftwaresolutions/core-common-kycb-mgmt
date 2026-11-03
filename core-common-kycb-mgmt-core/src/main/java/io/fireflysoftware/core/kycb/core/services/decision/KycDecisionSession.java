package io.fireflysoftware.core.kycb.core.services.decision;

import io.fireflysoftware.core.kycb.models.repositories.decision.*;
import io.fireflysoftware.core.kycb.models.repositories.decision.impl.KycDecisionStoreImpl;
import java.time.Duration;
import java.util.Objects;
import org.fireflyframework.core.transaction.*;
import org.fireflyframework.core.transaction.impl.DefaultBoundedRootTransactionImpl;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;

public final class KycDecisionSession {
    public enum Role { COMMAND, READER }
    private final Role role;private final BoundedR2dbcResources resources;private final R2dbcEntityTemplate operations;
    private final KycDecisionStore store;private final KycDecisionNativeRepository nativeRepository;private final BoundedRootTransaction transactions;
    public KycDecisionSession(Role role,OwnedLeaseConnectionFactoryImpl factory,DatabaseClient client,R2dbcEntityTemplate operations,R2dbcTransactionManager manager){
        this.role=Objects.requireNonNull(role);this.resources=BoundedR2dbcResources.verify(factory,client,operations,manager);this.operations=operations;this.store=new KycDecisionStoreImpl(operations);
        var repositories=new R2dbcRepositoryFactory(operations);repositories.setBeanClassLoader(KycDecisionNativeRepository.class.getClassLoader());this.nativeRepository=repositories.getRepository(KycDecisionNativeRepository.class);
        this.transactions=new DefaultBoundedRootTransactionImpl(resources,new CleanupPolicy(Duration.ofSeconds(2),Duration.ofSeconds(2)));
    }
    public Role role(){return role;}public BoundedR2dbcResources resources(){return resources;}public R2dbcEntityTemplate operations(){return operations;}
    public KycDecisionStore store(){return store;}public KycDecisionNativeRepository nativeRepository(){return nativeRepository;}public BoundedRootTransaction transactions(){return transactions;}
}
