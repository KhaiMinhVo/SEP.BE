package com.influencermatch.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.influencermatch.backend.support.*;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class ChangePasswordPostgresIntegrationTest extends PostgresApiTestSupport {
  String login(User user, String password, int expected) throws Exception {
    var result = mvc.perform(post("/auth/login").with(TestAuthHttp.csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(Map.of("email", user.getEmail(), "password", password))))
        .andExpect(status().is(expected)).andReturn();
    return json.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
  }

  MvcResult change(String token, String oldPassword, String newPassword, int expected) throws Exception {
    return mvc.perform(post("/auth/change-password").with(TestAuthHttp.csrf())
        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(Map.of("currentPassword", oldPassword, "newPassword", newPassword))))
        .andExpect(status().is(expected)).andReturn();
  }

  @Test void allRolesCanChangeOwnPasswordAndOldSessionsAreRevoked() throws Exception {
    for (var role : new Role[] {Role.ADMIN, Role.DATA_MANAGER, Role.BRAND, Role.CREATOR}) {
      var user = account(role);
      var first = login(user, "Password123!", 200);
      var second = login(user, "Password123!", 200);
      var other = login(account(Role.BRAND), "Password123!", 200);
      var result = change(first, "Password123!", "ChangedPassword123!", 204);
      assertThat(result.getResponse().getCookie("IM_REFRESH").getMaxAge()).isZero();
      assertThat(users.findById(user.getId()).orElseThrow().getAuthVersion()).isEqualTo(user.getAuthVersion() + 1);
      callToken(first, get("/auth/me"), null, 401);
      callToken(second, get("/auth/me"), null, 401);
      callToken(other, get("/auth/me"), null, 200);
      assertThat(jdbc.queryForObject("select count(*) from refresh_token where user_id=? and revoked_at is null", Integer.class, user.getId())).isZero();
      login(user, "Password123!", 401);
      var fresh = login(user, "ChangedPassword123!", 200);
      callToken(fresh, get("/auth/me"), null, 200);
    }
  }

  @Test void rejectsWrongPasswordShortPasswordMissingCsrfAndAnonymousRequests() throws Exception {
    var user = account(Role.BRAND);
    var token = login(user, "Password123!", 200);
    change(token, "WrongPassword123!", "ChangedPassword123!", 400);
    change(token, "Password123!", "short", 400);
    change(token, "Password123!", "Password123!", 400);
    var body = json.writeValueAsBytes(Map.of("currentPassword", "Password123!", "newPassword", "ChangedPassword123!"));
    mvc.perform(post("/auth/change-password").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    mvc.perform(post("/auth/change-password").with(TestAuthHttp.csrf())
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    callToken(token, get("/auth/me"), null, 200);
    login(user, "Password123!", 200);
  }
}
