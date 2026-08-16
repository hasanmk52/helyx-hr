package com.helyx.helyxhr.web;

import com.helyx.helyxhr.common.HelyxException;
import com.helyx.helyxhr.identity.AppUserPrincipal;
import com.helyx.helyxhr.timeoff.BalanceService;
import com.helyx.helyxhr.timeoff.LeaveType;
import com.helyx.helyxhr.timeoff.LeaveTypeService;
import com.helyx.helyxhr.timeoff.PublicHoliday;
import com.helyx.helyxhr.timeoff.PublicHolidayService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin CRUD for Leave Types and Holidays (PRD §12, §26: Admin-only). Two separate pages, unlike
 * {@code AdminOrganizationController}'s combined Division/Department page — a leave type and a
 * holiday don't need to show each other's data — but one controller class, following {@code
 * people.EmployeeService}'s (not {@code org.OrganizationAdminService}'s) shape: every
 * controller-facing mutation is a single combined write-then-read {@code @Transactional} method
 * on the owning service ({@code LeaveTypeService.createAndList} etc.), so this controller never
 * composes a write call and a separate read call itself (ADR 0007). No {@code @Transactional}
 * here (CLAUDE.md §7).
 *
 * <p>The manual balance-adjustment endpoint has no page this phase (CURRENT_PHASE.md/Implementation
 * Plan list it only under "Backend") — {@code @ResponseBody}, no view.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
class AdminLeaveController {

    private final LeaveTypeService leaveTypes;
    private final PublicHolidayService holidays;
    private final BalanceService balances;

    AdminLeaveController(LeaveTypeService leaveTypes, PublicHolidayService holidays, BalanceService balances) {
        this.leaveTypes = leaveTypes;
        this.holidays = holidays;
        this.balances = balances;
    }

    // ---- Leave Types ----

    @GetMapping("/admin/leave-types")
    @PreAuthorize("hasRole('ADMIN')")
    String leaveTypesPage(Model model) {
        model.addAttribute("leaveTypeForm", blankLeaveTypeForm());
        model.addAttribute("leaveTypes", leaveTypes.listAll());
        return "admin/leave-types";
    }

    @GetMapping("/admin/leave-types/new-form")
    @PreAuthorize("hasRole('ADMIN')")
    String newLeaveTypeForm(Model model) {
        model.addAttribute("leaveTypeForm", blankLeaveTypeForm());
        return "admin/leave-types :: leaveTypeForm";
    }

    @GetMapping("/admin/leave-types/{id}/edit-form")
    @PreAuthorize("hasRole('ADMIN')")
    String editLeaveTypeForm(@PathVariable UUID id, Model model) {
        LeaveType type = leaveTypes.require(id);
        model.addAttribute("leaveTypeForm", toForm(type));
        model.addAttribute("editingLeaveTypeId", id);
        return "admin/leave-types :: leaveTypeForm";
    }

    @PostMapping("/admin/leave-types")
    @PreAuthorize("hasRole('ADMIN')")
    String createLeaveType(
            @Valid @ModelAttribute("leaveTypeForm") LeaveConfigForms.LeaveTypeForm form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (!binding.hasErrors()) {
            try {
                List<LeaveType> fresh =
                        leaveTypes.createAndList(
                                form.name(),
                                form.icon(),
                                form.color(),
                                form.paid(),
                                form.allowsHalfDay(),
                                form.allowsBackdated(),
                                form.requiresApproval(),
                                form.defaultAnnualAllowance(),
                                form.description());
                return leaveTypeSaveSucceeded(response, model, "Leave type created", fresh);
            } catch (HelyxException exception) {
                binding.rejectValue("name", exception.errorCode(), exception.getMessage());
            }
        }
        return "admin/leave-types :: leaveTypeForm";
    }

    @PatchMapping("/admin/leave-types/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    String editLeaveType(
            @PathVariable UUID id,
            @Valid @ModelAttribute("leaveTypeForm") LeaveConfigForms.LeaveTypeForm form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (!binding.hasErrors()) {
            try {
                List<LeaveType> fresh =
                        leaveTypes.editAndList(
                                id,
                                form.name(),
                                form.icon(),
                                form.color(),
                                form.paid(),
                                form.allowsHalfDay(),
                                form.allowsBackdated(),
                                form.requiresApproval(),
                                form.defaultAnnualAllowance(),
                                form.description());
                return leaveTypeSaveSucceeded(response, model, "Leave type updated", fresh);
            } catch (HelyxException exception) {
                binding.rejectValue("name", exception.errorCode(), exception.getMessage());
            }
        }
        model.addAttribute("editingLeaveTypeId", id);
        return "admin/leave-types :: leaveTypeForm";
    }

    @PostMapping("/admin/leave-types/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    String activateLeaveType(@PathVariable UUID id, Model model, HttpServletResponse response) {
        toast(response, "Leave type activated");
        model.addAttribute("leaveTypes", leaveTypes.activateAndList(id));
        return "admin/leave-types :: content";
    }

    @PostMapping("/admin/leave-types/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    String deactivateLeaveType(@PathVariable UUID id, Model model, HttpServletResponse response) {
        toast(response, "Leave type deactivated");
        model.addAttribute("leaveTypes", leaveTypes.deactivateAndList(id));
        return "admin/leave-types :: content";
    }

    // ---- Holidays ----

    @GetMapping("/admin/holidays")
    @PreAuthorize("hasRole('ADMIN')")
    String holidaysPage(Model model) {
        model.addAttribute("holidayForm", blankHolidayForm());
        model.addAttribute("holidays", holidays.listAll());
        model.addAttribute("uploadErrors", List.<String>of());
        return "admin/holidays";
    }

    @GetMapping("/admin/holidays/new-form")
    @PreAuthorize("hasRole('ADMIN')")
    String newHolidayForm(Model model) {
        model.addAttribute("holidayForm", blankHolidayForm());
        return "admin/holidays :: holidayForm";
    }

    @PostMapping("/admin/holidays")
    @PreAuthorize("hasRole('ADMIN')")
    String createHoliday(
            @Valid @ModelAttribute("holidayForm") LeaveConfigForms.HolidayForm form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (!binding.hasErrors()) {
            try {
                List<PublicHoliday> fresh = holidays.createAndList(form.date(), form.name());
                toast(response, "Holiday added");
                model.addAttribute("holidays", fresh);
                model.addAttribute("refreshTable", true);
                model.addAttribute("holidayForm", blankHolidayForm());
                return "admin/holidays :: holidayForm";
            } catch (HelyxException exception) {
                binding.rejectValue("name", exception.errorCode(), exception.getMessage());
            }
        }
        return "admin/holidays :: holidayForm";
    }

    @DeleteMapping("/admin/holidays/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    String deleteHoliday(@PathVariable UUID id, Model model, HttpServletResponse response) {
        toast(response, "Holiday deleted");
        model.addAttribute("holidays", holidays.deleteAndList(id));
        model.addAttribute("uploadErrors", List.<String>of());
        return "admin/holidays :: content";
    }

    @PostMapping("/admin/holidays/upload")
    @PreAuthorize("hasRole('ADMIN')")
    String uploadHolidays(
            @RequestParam("file") MultipartFile file, Model model, HttpServletResponse response) {
        PublicHolidayService.CsvUploadResult result;
        try {
            result = holidays.bulkUpload(file);
        } catch (HelyxException exception) {
            model.addAttribute("uploadErrors", List.of(exception.getMessage()));
            model.addAttribute("holidays", holidays.listAll());
            return "admin/holidays :: content";
        }
        if (result.errors().isEmpty()) {
            toast(response, "Uploaded " + result.createdCount() + " holiday(s)");
        }
        model.addAttribute("uploadErrors", result.errors());
        model.addAttribute("holidays", result.holidays());
        return "admin/holidays :: content";
    }

    // ---- Manual balance adjustment (backend-only, no dedicated screen this phase) ----

    @PostMapping("/admin/employees/{employeeId}/leave-balances/{leaveTypeId}/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseBody
    void adjustBalance(
            @PathVariable UUID employeeId,
            @PathVariable UUID leaveTypeId,
            @Valid @ModelAttribute LeaveConfigForms.BalanceAdjustmentForm form,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        balances.adjustManually(
                employeeId, leaveTypeId, form.newGranted(), form.reason(), principal.getUsername());
    }

    private String leaveTypeSaveSucceeded(
            HttpServletResponse response, Model model, String message, List<LeaveType> fresh) {
        toast(response, message);
        model.addAttribute("leaveTypes", fresh);
        model.addAttribute("refreshTable", true);
        model.addAttribute("leaveTypeForm", blankLeaveTypeForm());
        return "admin/leave-types :: leaveTypeForm";
    }

    private static LeaveConfigForms.LeaveTypeForm blankLeaveTypeForm() {
        return new LeaveConfigForms.LeaveTypeForm(
                "", null, null, true, true, false, true, BigDecimal.ZERO, null);
    }

    private static LeaveConfigForms.HolidayForm blankHolidayForm() {
        return new LeaveConfigForms.HolidayForm(null, "");
    }

    private static LeaveConfigForms.LeaveTypeForm toForm(LeaveType type) {
        return new LeaveConfigForms.LeaveTypeForm(
                type.name(),
                type.icon(),
                type.color(),
                type.paid(),
                type.allowsHalfDay(),
                type.allowsBackdated(),
                type.requiresApproval(),
                type.defaultAnnualAllowance(),
                type.description());
    }

    /**
     * htmx surfaces this via the {@code HX-Trigger} response header; {@code helyx.js} listens for
     * {@code organization-toast} to show a Bootstrap toast and close any open offcanvas — reusing
     * the existing event name rather than inventing a new one (both current admin controllers
     * already share it).
     */
    private static void toast(HttpServletResponse response, String message) {
        response.setHeader("HX-Trigger", "{\"organization-toast\": {\"message\": \"" + message + "\"}}");
    }
}
