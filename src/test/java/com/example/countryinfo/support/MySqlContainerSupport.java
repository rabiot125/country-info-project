package com.example.countryinfo.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;

/**
 * One real MySQL 8 shared by every integration test class (singleton container pattern;
 * Testcontainers' Ryuk removes it when the JVM exits). Wired into Spring via @ServiceConnection.
 *
 * <p>Real MySQL rather than H2 so the Flyway SQL, the unique constraint and the collation
 * behave exactly as they do in production.
 */
public abstract class MySqlContainerSupport {

    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("countryinfo")
            .withUsername("countryinfo")
            .withPassword("test-only-password");

    static {
        MYSQL.start();
    }
}
