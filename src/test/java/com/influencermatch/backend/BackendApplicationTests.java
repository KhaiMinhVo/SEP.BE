package com.influencermatch.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import com.influencermatch.backend.repository.UserRepository;
import com.influencermatch.backend.entity.Role;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

/**
 * Smoke test that verifies the Spring application context loads without errors.
 *
 * <p>Annotated with {@code @TestPropertySource} to override the datasource URL with
 * an in-memory H2 database (or a Testcontainers Postgres) to avoid requiring a live
 * PostgreSQL instance during CI. Replace the in-memory override with a Testcontainers
 * configuration as the test suite matures.
 */
@SpringBootTest
@AutoConfigureMockMvc
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

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    @Test
    void contextLoads() {
        // Spring context must start without throwing exceptions.
    }

    @Test
    void unauthenticatedRequestUsesProblemDetailAndCorrelationId() throws Exception {
        mvc.perform(get("/auth/me").header("X-Correlation-ID", "mvc-trace"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-ID", "mvc-trace"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").value("mvc-trace"));
    }

    @Test
    void publicRegistrationAlwaysCreatesBrand() throws Exception {
        String email="registered-brand@test.local";
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\""+email+"\",\"password\":\"Password123!\",\"fullName\":\"Registered Brand\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true));
        assertThat(users.findByEmail(email)).get().extracting("role").isEqualTo(Role.BRAND);
    }

    @Test
    void validationUsesProblemDetailWithFieldErrors() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"fullName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.email").isArray())
                .andExpect(jsonPath("$.fieldErrors.password").isArray());
    }
}
