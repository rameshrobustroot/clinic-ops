package com.clinicops.app.web.dto;

import com.clinicops.app.domain.Patient;
import com.clinicops.app.util.ValidationPatterns;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PatientForm {

    @NotBlank(message = "Name is required")
    @Size(max = 128)
    private String name;

    @NotBlank(message = "Phone is required")
    @Pattern(regexp = ValidationPatterns.PHONE_INDIA, message = "Enter a 10-digit mobile starting with 6/7/8/9")
    private String phone;

    @Min(value = 0, message = "Age cannot be negative")
    @Max(value = 130, message = "Age cannot exceed 130")
    private Integer age;
    private String gender;
    private String address;
    private String allergies;
    private String notes;

    public Patient toEntity() {
        return Patient.builder()
                .name(name)
                .phone(phone)
                .age(age)
                .gender(gender)
                .address(address)
                .allergies(allergies)
                .notes(notes)
                .build();
    }

    public static PatientForm from(Patient p) {
        PatientForm f = new PatientForm();
        f.name = p.getName();
        f.phone = p.getPhone();
        f.age = p.getAge();
        f.gender = p.getGender();
        f.address = p.getAddress();
        f.allergies = p.getAllergies();
        f.notes = p.getNotes();
        return f;
    }
}
