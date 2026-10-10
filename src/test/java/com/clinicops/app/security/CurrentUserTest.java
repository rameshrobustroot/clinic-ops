package com.clinicops.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Role;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    @Test
    void no_authentication_throws_illegal_state() {
        assertThatThrownBy(currentUser::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No authenticated user");
        assertThatThrownBy(currentUser::clinicId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(currentUser::userId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void non_app_user_details_principal_throws_illegal_state() {
        authenticate(User.withUsername("plain").password("x").roles("OWNER").build());

        assertThatThrownBy(currentUser::get).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void string_principal_such_as_anonymous_throws_illegal_state() {
        authenticate("anonymousUser");

        assertThatThrownBy(currentUser::get).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void returns_app_user_details_and_exposes_clinic_and_user_ids() {
        AppUserDetails details = new AppUserDetails(AppUser.builder()
                .id(11L).clinicId(22L).username("u").passwordHash("p").role(Role.OWNER).build());
        authenticate(details);

        assertThat(currentUser.get()).isSameAs(details);
        assertThat(currentUser.clinicId()).isEqualTo(22L);
        assertThat(currentUser.userId()).isEqualTo(11L);
    }
}
