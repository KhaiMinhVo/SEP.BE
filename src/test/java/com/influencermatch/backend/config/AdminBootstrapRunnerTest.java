package com.influencermatch.backend.config;

import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminBootstrapRunnerTest {
    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test void disabledDoesNothing() {
        BootstrapAdminProperties p = properties(false, "", "");
        new AdminBootstrapRunner(p, users, encoder).run(new DefaultApplicationArguments());
        verifyNoInteractions(users);
    }

    @Test void createsActiveAdminWithBcryptPassword() {
        BootstrapAdminProperties p = properties(true, " Admin@Example.com ", "StrongPassword123!");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        new AdminBootstrapRunner(p, users, encoder).run(new DefaultApplicationArguments());
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("admin@example.com");
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(encoder.matches("StrongPassword123!", saved.getPassword())).isTrue();
    }

    @Test void existingActiveAdminIsIdempotent() {
        BootstrapAdminProperties p = properties(true, "admin@example.com", "StrongPassword123!");
        User admin = User.builder().email("admin@example.com").role(Role.ADMIN).status(UserStatus.ACTIVE).build();
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        new AdminBootstrapRunner(p, users, encoder).run(new DefaultApplicationArguments());
        verify(users, never()).save(any());
    }

    @Test void reactivatesExistingAdmin() {
        BootstrapAdminProperties p = properties(true, "admin@example.com", "StrongPassword123!");
        User admin = User.builder().email("admin@example.com").role(Role.ADMIN).status(UserStatus.DISABLED).build();
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        new AdminBootstrapRunner(p, users, encoder).run(new DefaultApplicationArguments());
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(users).save(admin);
    }

    @Test void neverPromotesBrandToAdmin() {
        BootstrapAdminProperties p = properties(true, "admin@example.com", "StrongPassword123!");
        User brand = User.builder().email("admin@example.com").role(Role.BRAND).status(UserStatus.ACTIVE).build();
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(brand));
        assertThatThrownBy(() -> new AdminBootstrapRunner(p, users, encoder).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("non-ADMIN");
        verify(users, never()).save(any());
    }

    @Test void rejectsInvalidEnabledConfiguration() {
        assertThatThrownBy(() -> properties(true, "bad-email", "short").validateWhenEnabled())
                .isInstanceOf(IllegalStateException.class);
    }

    private BootstrapAdminProperties properties(boolean enabled, String email, String password) {
        BootstrapAdminProperties p = new BootstrapAdminProperties();
        p.setEnabled(enabled); p.setEmail(email); p.setPassword(password); p.setFullName("PoC Administrator");
        return p;
    }
}


