package com.clinicops.app.repository;

import com.clinicops.app.domain.AppUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    // NOTE: there is deliberately no findByUsername(String). Usernames are unique per clinic
    // (uk_appuser_clinic_username), so a bare-username lookup can match several rows — and a
    // clinic could then break platform-admin login or app startup just by picking a name.

    /** Platform-admin login / bootstrap: bare username, scoped to the PLATFORM_ADMIN role. */
    Optional<AppUser> findByRoleAndUsername(com.clinicops.app.domain.Role role, String username);

    /** Used at login: resolves a (clinicCode, username) pair to the right user across tenants. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT u FROM AppUser u WHERE u.username = :username " +
            "AND u.clinicId = (SELECT c.id FROM Clinic c WHERE c.code = :clinicCode)")
    Optional<AppUser> findByClinicCodeAndUsername(
            @org.springframework.data.repository.query.Param("clinicCode") String clinicCode,
            @org.springframework.data.repository.query.Param("username") String username);

    Optional<AppUser> findByClinicIdAndUsername(Long clinicId, String username);

    List<AppUser> findByClinicIdOrderByFullNameAsc(Long clinicId);

    long count();
}
