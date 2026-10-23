package com.influencermatch.backend.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.security.jwt.JwtTokenProvider;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** One disposable database per JVM shared by domain suites; fixtures use unique owners/IDs.
 * Testcontainers/Ryuk cleans up the singleton at JVM exit, avoiding cached contexts with stopped DBs. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
    "application.bootstrap-admin.enabled=false", "application.oauth2.google.enabled=false",
    "application.auth.creator-mock.enabled=false", "application.payment.mock.enabled=false",
    "application.claim-email.dispatch-enabled=false",
    "application.claim-email.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "spring.jpa.show-sql=false"})
@ActiveProfiles("dev") @AutoConfigureMockMvc
public abstract class PostgresApiTestSupport {
  private static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
  static { DB.start(); }
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);
    r.add("spring.datasource.password",DB::getPassword);
  }
  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper json;
  @Autowired protected UserRepository users;
  @Autowired protected PasswordEncoder encoder;
  @Autowired protected JwtTokenProvider jwt;
  @Autowired protected JdbcTemplate jdbc;
  protected User account(Role role) {
    var user=TestActors.user(role);user.setId(null);user.setPasswordHash(encoder.encode("Password123!"));
    return users.saveAndFlush(user);
  }
  protected JsonNode call(User user,MockHttpServletRequestBuilder req,Object body,int expected) throws Exception {
    return callToken(user==null?null:jwt.generateToken(user),req,body,expected);
  }
  protected JsonNode callToken(String token,MockHttpServletRequestBuilder req,Object body,int expected) throws Exception {
    if(token!=null)req.header("Authorization","Bearer "+token);
    if(body!=null)req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
    var result=mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse();
    return result.getContentAsString().isEmpty()?json.nullNode():json.readTree(result.getContentAsString());
  }
  protected JsonNode login(User user) throws Exception {
    return call(null,org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/login"),
        Map.of("email",user.getEmail(),"password","Password123!"),200).path("data");
  }
  protected UUID createBrand(User user) throws Exception {
    return UUID.fromString(call(user,org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/brands"),
        Map.of("businessName","Test Brand"),201).path("data").path("id").asText());
  }
  protected Map<String,Object> campaignBody() {
    var body=new HashMap<String,Object>();body.put("name","Test Campaign");body.put("productService","Product");
    body.put("objective","AWARENESS");body.put("platforms",List.of("TIKTOK"));return body;
  }
  protected UUID createCampaign(User user,UUID brand) throws Exception {
    return UUID.fromString(call(user,org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/brands/"+brand+"/campaigns"),
        campaignBody(),201).path("data").path("id").asText());
  }
}
