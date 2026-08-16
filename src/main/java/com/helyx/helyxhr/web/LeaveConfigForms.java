package com.helyx.helyxhr.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

/** Form-backing records for the Leave Types / Holidays admin pages (CLAUDE.md §7: DTOs are records). */
final class LeaveConfigForms {

    private LeaveConfigForms() {}

    record LeaveTypeForm(
            @NotBlank @Size(max = 100) String name,
            @Nullable @Size(max = 50) String icon,
            @Nullable @Size(max = 7) String color,
            boolean paid,
            boolean allowsHalfDay,
            boolean allowsBackdated,
            boolean requiresApproval,
            @NotNull @DecimalMin("0.0") BigDecimal defaultAnnualAllowance,
            @Nullable String description) {}

    record HolidayForm(
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @NotBlank @Size(max = 200) String name) {}

    /** Backs the manual balance adjustment endpoint (PRD §12.2's "required reason"). */
    record BalanceAdjustmentForm(
            @NotNull @DecimalMin("0.0") BigDecimal newGranted, @NotBlank @Size(max = 500) String reason) {}
}
