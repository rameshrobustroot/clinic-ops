package com.clinicops.app.web;

import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.AlertsService;
import com.clinicops.app.service.DashboardService;
import com.clinicops.app.service.ProcurementService;
import com.clinicops.app.service.SupplierBillService;
import com.clinicops.app.service.VisitService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final AlertsService alertsService;
    private final VisitService visitService;
    private final ProcurementService procurementService;
    private final SupplierBillService supplierBillService;
    private final CurrentUser currentUser;

    @GetMapping("/")
    public String home(Model model) {
        Long clinicId = currentUser.clinicId();
        model.addAttribute("dashboard", dashboardService.load(clinicId));
        AlertsService.AlertsView alerts = alertsService.load(clinicId);
        model.addAttribute("expiredCount", alerts.expired().size());
        model.addAttribute("criticalCount", alerts.critical().size());
        model.addAttribute("lowStockCount", alerts.lowStock().size());
        model.addAttribute("followUpsToday", visitService.followUpsFor(clinicId, LocalDate.now()));
        model.addAttribute("pendingProcurement", procurementService.pendingCount(clinicId));
        model.addAttribute("outstandingBills", supplierBillService.outstandingTotal(clinicId));
        model.addAttribute("overdueBillCount", supplierBillService.overdueCount(clinicId));
        return "dashboard/home";
    }
}
