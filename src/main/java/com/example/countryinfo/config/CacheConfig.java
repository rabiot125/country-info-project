package com.example.countryinfo.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Caffeine is configured in application.yml (spring.cache.*) so TTL and size can be tuned
 * per environment, and Spring Boot registers cache.gets/cache.puts metrics for it.
 *
 * <p>The cache advice runs with the highest precedence so it sits outside the Resilience4j
 * aspects: a cache hit must succeed even while the circuit breaker is open.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class CacheConfig {

    public static final String ISO_CODE_CACHE = "isoCodes";
}
