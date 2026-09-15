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
import com.influencermatch.backend.entity.User;
import com.influencermatch.backend.entity.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;

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

    @Test
    void completeRegisterLoginAndRoleFlow() throws Exception {
        String brandEmail = "poc-brand@test.local";
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + brandEmail + "\",\"password\":\"Password123!\",\"fullName\":\"PoC Brand\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated());
        assertThat(users.findByEmail(brandEmail)).get().extracting("role").isEqualTo(Role.BRAND);

        JsonNode brandLogin = json(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + brandEmail + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String brandAccess = brandLogin.at("/data/accessToken").asText();
        String brandRefresh = brandLogin.at("/data/refreshToken").asText();

        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + brandAccess))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("BRAND"));
        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + brandAccess))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        String adminEmail = "poc-admin@test.local";
        users.save(User.builder().email(adminEmail).password(passwordEncoder.encode("AdminPassword123!"))
                .fullName("PoC Admin").role(Role.ADMIN).status(UserStatus.ACTIVE).build());
        JsonNode adminLogin = json(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"AdminPassword123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String adminAccess = adminLogin.at("/data/accessToken").asText();
        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + adminAccess))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());

        JsonNode rotated = json(mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + brandRefresh + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String newAccess = rotated.at("/data/accessToken").asText();
        String newRefresh = rotated.at("/data/refreshToken").asText();
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + newAccess)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private JsonNode json(String value) throws Exception { return objectMapper.readTree(value); }
}
