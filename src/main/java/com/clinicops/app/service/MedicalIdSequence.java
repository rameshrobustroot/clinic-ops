package com.clinicops.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates year-prefixed running identifiers (MRN, BILL, OPD/IPD encounter numbers).
 *
 * <p>Format: {@code PREFIX-YYYY-NNNNN} (sequence resets each calendar year). Numbers are unique
 * across ALL clinics — the underlying columns carry global unique constraints.
 *
 * <p>Backed by the {@code id_sequence} table: one row per {@code PREFIX-YYYY} key, bumped with an
 * {@code UPDATE} that row-locks until the caller's transaction ends, so concurrent callers
 * serialise instead of colliding, and a rolled-back caller leaves no gap. Everything here is
 * native SQL on purpose: the Hibernate {@code tenantFilter} never applies to native queries, so a
 * clinic-scoped request can't narrow the view and re-issue another clinic's number.
 *
 * <p>Every call consumes a number — only call when the result will be saved.
 */
@Component
@RequiredArgsConstructor
public class MedicalIdSequence {

    private final EntityManager entityManager;

    @Transactional
    public String nextPatientMrn() {
        return next("MRN", "patient", "mrn");
    }

    @Transactional
    public String nextBillNo() {
        return next("BILL", "sale", "bill_no");
    }

    /** Generates next encounter number for the given type (OPD today, IPD later). */
    @Transactional
    public String nextEncounterNo(String encounterType) {
        String type = (encounterType == null || encounterType.isBlank()) ? "OPD" : encounterType.trim();
        return next(type, "visit", "encounter_no");
    }

    /** {@code table} / {@code column} are compile-time constants from the callers above, never input. */
    private String next(String prefix, String table, String column) {
        int year = LocalDate.now(ZoneId.systemDefault()).getYear();
        String key = prefix + "-" + year;

        if (increment(key) == 0) {
            // First use of this key (new year / new prefix, or first run after migration 032):
            // continue from the highest number already issued so existing rows never collide.
            long seed = highestInUse(table, column, key + "-");
            nativeQuery("INSERT INTO id_sequence (seq_key, counter) VALUES (?1, ?2) ON CONFLICT DO NOTHING")
                    .setParameter(1, key)
                    .setParameter(2, seed)
                    .executeUpdate();
            increment(key);
        }

        Number value = (Number) nativeQuery("SELECT counter FROM id_sequence WHERE seq_key = ?1")
                .setParameter(1, key)
                .getSingleResult();
        return String.format("%s-%d-%05d", prefix, year, value.longValue());
    }

    private int increment(String key) {
        return nativeQuery("UPDATE id_sequence SET counter = counter + 1 WHERE seq_key = ?1")
                .setParameter(1, key)
                .executeUpdate();
    }

    private long highestInUse(String table, String column, String keyPrefix) {
        @SuppressWarnings("unchecked")
        List<Object> values = entityManager
                .createNativeQuery("SELECT " + column + " FROM " + table + " WHERE " + column + " LIKE ?1")
                .setParameter(1, keyPrefix + "%")
                .getResultList();
        long max = 0;
        for (Object v : values) {
            String suffix = v.toString().substring(keyPrefix.length());
            if (suffix.matches("\\d{1,18}")) max = Math.max(max, Long.parseLong(suffix));
        }
        return max;
    }

    /** Scoped to id_sequence so these statements don't force a flush of unrelated pending entities. */
    private Query nativeQuery(String sql) {
        Query q = entityManager.createNativeQuery(sql);
        q.unwrap(NativeQuery.class).addSynchronizedQuerySpace("id_sequence");
        return q;
    }
}
