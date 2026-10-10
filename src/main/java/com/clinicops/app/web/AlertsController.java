package com.clinicops.app.web;

import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.AlertsService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/alerts")
@RequiredArgsConstructor
public class AlertsController {

    private final AlertsService alertsService;
    private final CurrentUser currentUser;

    @GetMapping
    public String view(Model model) {
        AlertsService.AlertsView view = alertsService.load(currentUser.clinicId());
        model.addAttribute("view", view);
        model.addAttribute("today", LocalDate.now());
        return "alerts/view";
    }
}
