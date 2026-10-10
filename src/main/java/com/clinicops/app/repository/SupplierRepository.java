package com.clinicops.app.repository;

import com.clinicops.app.domain.Supplier;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    List<Supplier> findByClinicIdOrderByNameAsc(Long clinicId);
}
