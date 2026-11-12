package com.influencermatch.backend.user.service;

import static org.assertj.core.api.Assertions.*;

import com.influencermatch.backend.auth.dto.UpdateUserStatusRequest;
import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.user.dto.UpdateUserRoleRequest;
import com.influencermatch.backend.user.enums.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class AdminAccountContractTest {
  @Test
  void paginationIsStrictAndHasStableOrdering() {
    for (int size : new int[] {0, -1, 101})
      assertThatThrownBy(() -> UserAccountSearch.page(0, size))
          .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> UserAccountSearch.page(-1, 20))
        .isInstanceOf(ValidationException.class);
    var page = UserAccountSearch.page(0, 20);
    assertThat(page.getSort().getOrderFor("createdAt").getDirection())
        .isEqualTo(Sort.Direction.DESC);
    assertThat(page.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
  }

  @Test
  void searchLimitsAndTrimPreserveLiteralCharacters() {
    assertThat(UserAccountSearch.query("  A_%!  ")).isEqualTo("A_%!");
    assertThat(UserAccountSearch.query(null)).isNull();
    assertThatThrownBy(() -> UserAccountSearch.query("x".repeat(201)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void reasonsAreNormalizedAndMandatoryForBothRequests() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var v = factory.getValidator();
      for (String reason : new String[] {null, "", "  ", "x".repeat(501)}) {
        assertThat(v.validate(new UpdateUserStatusRequest(UserStatus.ACTIVE, reason))).isNotEmpty();
        assertThat(v.validate(new UpdateUserRoleRequest(Role.ADMIN, reason))).isNotEmpty();
      }
      var status = new UpdateUserStatusRequest(UserStatus.ACTIVE, "  Confirmed  ");
      var role = new UpdateUserRoleRequest(Role.ADMIN, "  Confirmed  ");
      assertThat(status.reason()).isEqualTo("Confirmed");
      assertThat(role.reason()).isEqualTo("Confirmed");
      assertThat(v.validate(status)).isEmpty();
      assertThat(v.validate(role)).isEmpty();
    }
  }
}
