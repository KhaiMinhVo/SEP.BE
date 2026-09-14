package com.influencermatch.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test that verifies the Spring application context loads without errors.
 *
 * <p>Annotated with {@code @TestPropertySource} to override the datasource URL with
 * an in-memory H2 database (or a Testcontainers Postgres) to avoid requiring a live
 * PostgreSQL instance during CI. Replace the in-memory override with a Testcontainers
 * configuration as the test suite matures.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "application.security.jwt.secret-key=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970",
        "application.security.jwt.expiration=86400000",
        "application.security.jwt.refresh-token.expiration=604800000"
})
class BackendApplicationTests {

    @Test
    void contextLoads() {
        // Spring context must start without throwing exceptions.
    }
}
