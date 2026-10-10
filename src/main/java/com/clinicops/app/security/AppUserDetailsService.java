package com.clinicops.app.security;

import com.clinicops.app.domain.Role;
import com.clinicops.app.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    /**
     * The login form sends either:
     * <ul>
     *   <li>{@code "code/username"} — normal clinic login. Resolves only inside that clinic.</li>
     *   <li>{@code "username"} (no slash) — platform-admin login. Resolves <em>only</em> if the
     *       matching user has role {@link Role#PLATFORM_ADMIN}; any other role here is rejected
     *       so a clinic user can't bypass the tenant binding by dropping the prefix.</li>
     * </ul>
     */
    @Override
    public UserDetails loadUserByUsername(String composite) {
        int slash = composite == null ? -1 : composite.indexOf('/');
        if (slash > 0 && slash < composite.length() - 1) {
            String clinicCode = composite.substring(0, slash).trim().toLowerCase();
            String username = composite.substring(slash + 1).trim().toLowerCase();
            return userRepository
                    .findByClinicCodeAndUsername(clinicCode, username)
                    .map(AppUserDetails::new)
                    .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        }
        // No slash → must be a platform admin. Reject everything else so clinic users can't
        // escape tenant scoping by omitting their clinic code.
        return userRepository
                .findByRoleAndUsername(Role.PLATFORM_ADMIN, composite == null ? "" : composite.trim().toLowerCase())
                .map(AppUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
