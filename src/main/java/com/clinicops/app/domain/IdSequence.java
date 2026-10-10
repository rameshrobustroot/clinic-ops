package com.clinicops.app.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Global running counter behind {@link com.clinicops.app.service.MedicalIdSequence}, one row per
 * {@code PREFIX-YYYY} key (e.g. {@code MRN-2026}). Deliberately NOT tenant-scoped: MRN / bill /
 * encounter numbers are unique across all clinics. Only touched via native SQL so the Hibernate
 * {@code tenantFilter} can never apply to it.
 */
@Entity
@Table(name = "id_sequence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IdSequence {

    @Id
    @Column(name = "seq_key", length = 40)
    private String seqKey;

    @Column(nullable = false)
    private Long counter;
}
