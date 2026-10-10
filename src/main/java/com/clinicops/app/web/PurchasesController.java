package com.clinicops.app.web;

import com.clinicops.app.domain.Supplier;
import com.clinicops.app.domain.SupplierBill;
import com.clinicops.app.repository.SupplierRepository;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.SupplierBillService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/purchases")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
public class PurchasesController {

    private final SupplierBillService supplierBillService;
    private final SupplierRepository supplierRepository;
    private final CurrentUser currentUser;

    @GetMapping
    public String hub(Model model) {
        Long clinicId = currentUser.clinicId();

        // Bill aggregates
        model.addAttribute("outstandingTotal", supplierBillService.outstandingTotal(clinicId));
        model.addAttribute("overdueCount", supplierBillService.overdueCount(clinicId));

        // Recent unpaid/overdue bills (max 5) ordered by urgency (due date asc)
        List<SupplierBill> recentUnpaid = supplierBillService.listAll(clinicId).stream()
                .filter(b -> !"PAID".equals(b.getStatus()))
                .limit(5)
                .collect(Collectors.toList());
        model.addAttribute("recentUnpaidBills", recentUnpaid);

        // Supplier name map for bill preview
        List<Supplier> suppliers = supplierRepository.findByClinicIdOrderByNameAsc(clinicId);
        Map<Long, String> supplierNames = suppliers.stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName));
        model.addAttribute("supplierNames", supplierNames);

        // Suppliers section — show count + first 5 as preview
        model.addAttribute("supplierCount", suppliers.size());
        model.addAttribute("supplierPreview",
                suppliers.stream().limit(5).collect(Collectors.toList()));

        return "purchases/hub";
    }
}
