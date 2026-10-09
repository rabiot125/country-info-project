package com.example.countryinfo.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Logs retry attempts and circuit-breaker state changes so they are searchable next to request logs. */
@Configuration(proxyBeanMethods = false)
public class ResilienceEventLoggingConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceEventLoggingConfig.class);

    @Bean
    public RegistryEventConsumer<Retry> retryEventLogger() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<Retry> event) {
                event.getAddedEntry().getEventPublisher().onRetry(e -> log.warn(
                        "resilience.retry name={} attempt={} waitMs={} cause={}",
                        e.getName(),
                        e.getNumberOfRetryAttempts(),
                        e.getWaitInterval().toMillis(),
                        e.getLastThrowable() == null ? "n/a" : e.getLastThrowable().getClass().getSimpleName()));
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<Retry> event) {
                // no-op
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<Retry> event) {
                // no-op
            }
        };
    }

    @Bean
    public RegistryEventConsumer<CircuitBreaker> circuitBreakerEventLogger() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> event) {
                event.getAddedEntry().getEventPublisher().onStateTransition(e -> log.warn(
                        "resilience.circuit_transition name={} transition={}",
                        e.getCircuitBreakerName(), e.getStateTransition()));
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> event) {
                // no-op
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> event) {
                // no-op
            }
        };
    }
}
