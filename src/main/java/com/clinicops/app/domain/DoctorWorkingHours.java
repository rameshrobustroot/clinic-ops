package com.clinicops.app.domain;

import jakarta.persistence.*;
import java.time.LocalTime;
import lombok.*;
import org.hibernate.annotations.Filter;

/**
 * A doctor's working hours for one day of the week.
 *
 * <p>{@code dayOfWeek} follows ISO-8601: 1=Monday … 7=Sunday.
 * A doctor can have multiple sessions on the same day (e.g. 09:00–13:00 and 17:00–20:00)
 * by having two rows with the same {@code dayOfWeek}.
 */
@Entity
@Table(name = "doctor_working_hours")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorWorkingHours implements com.clinicops.app.tenant.TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    /** ISO day of week: 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri, 6=Sat, 7=Sun */
    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;
}
