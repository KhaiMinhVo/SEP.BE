package com.influencermatch.backend.auth.dto;

import com.influencermatch.backend.auth.controller.*;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.auth.service.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterRequest {
  @com.fasterxml.jackson.annotation.JsonSetter(value="role",nulls=com.fasterxml.jackson.annotation.Nulls.FAIL)
  public void rejectRole(Object role) {
    throw new com.influencermatch.backend.exception.ValidationException("Public registration is for Brand only; role is not accepted");
  }


  @NotBlank(message = "Email is required.")
  @Email(message = "Email must be a valid address.")
  private String email;

  @NotBlank(message = "Password is required.")
  @Size(min = 8, message = "Password must be at least 8 characters.")
  private String password;

  @NotBlank(message = "Full name is required.")
  @Size(max = 150, message = "Full name must not exceed 150 characters.")
  private String fullName;
}
