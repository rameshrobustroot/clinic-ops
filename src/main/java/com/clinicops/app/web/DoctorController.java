package com.clinicops.app.web;

import com.clinicops.app.domain.Doctor;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.DoctorService;
import com.clinicops.app.service.MedicalMasterService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/doctors")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class DoctorController {

    private final DoctorService doctorService;
    private final MedicalMasterService masterService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    public String list(Model model) {
        model.addAttribute("doctors", doctorService.listAll(currentUser.clinicId()));
        return "doctor/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("doctor", new Doctor());
        addMasterListsToModel(model);
        return "doctor/form";
    }

    private void addMasterListsToModel(Model model) {
        var degrees = masterService.listActiveDegrees(currentUser.clinicId());
        var specs = masterService.listActiveSpecializations(currentUser.clinicId());
        model.addAttribute("masterDegrees", degrees);
        model.addAttribute("masterSpecializations", specs);
        try {
            model.addAttribute("masterDegreesJson",
                    objectMapper.writeValueAsString(degrees.stream().map(d -> d.getName()).toList()));
            model.addAttribute("masterSpecializationsJson",
                    objectMapper.writeValueAsString(specs.stream().map(s -> s.getName()).toList()));
        } catch (JsonProcessingException e) {
            model.addAttribute("masterDegreesJson", "[]");
            model.addAttribute("masterSpecializationsJson", "[]");
        }
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("doctor") Doctor doctor, BindingResult br,
                         Model model, RedirectAttributes ra) {
        if (br.hasErrors()) {
            model.addAttribute("error", br.getAllErrors().get(0).getDefaultMessage());
            addMasterListsToModel(model);
            return "doctor/form";
        }
        Doctor saved = doctorService.create(currentUser.clinicId(), doctor);
        ra.addFlashAttribute("flash", "Doctor added: Dr " + saved.getFullName());
        return "redirect:/doctors";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("doctor", doctorService.getOrThrow(id));
        addMasterListsToModel(model);
        return "doctor/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("doctor") Doctor doctor, BindingResult br,
                         Model model, RedirectAttributes ra) {
        if (br.hasErrors()) {
            model.addAttribute("error", br.getAllErrors().get(0).getDefaultMessage());
            addMasterListsToModel(model);
            return "doctor/form";
        }
        doctorService.update(id, doctor);
        ra.addFlashAttribute("flash", "Doctor updated.");
        return "redirect:/doctors";
    }

    @PostMapping("/{id}/delete")
    public String softDelete(@PathVariable Long id, RedirectAttributes ra) {
        doctorService.softDelete(id);
        ra.addFlashAttribute("flash", "Doctor deactivated.");
        return "redirect:/doctors";
    }

    @PostMapping("/{id}/restore")
    public String restore(@PathVariable Long id, RedirectAttributes ra) {
        doctorService.restore(id);
        ra.addFlashAttribute("flash", "Doctor reactivated.");
        return "redirect:/doctors";
    }
}
