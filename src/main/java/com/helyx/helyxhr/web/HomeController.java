package com.helyx.helyxhr.web;

import com.helyx.helyxhr.identity.AppUserPrincipal;
import com.helyx.helyxhr.people.Employee;
import com.helyx.helyxhr.people.EmployeeService;
import com.helyx.helyxhr.timeoff.BalanceService;
import com.helyx.helyxhr.timeoff.LeaveBalance;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class HomeController {

    private final EmployeeService employees;
    private final BalanceService balances;

    HomeController(EmployeeService employees, BalanceService balances) {
        this.employees = employees;
        this.balances = balances;
    }

    /**
     * Any signed-in user of this tenant; the role hierarchy makes EMPLOYEE the floor.
     *
     * <p>{@code firstName} is null for a principal with no linked {@link Employee} — an Admin-only
     * account such as {@code DevDataSeeder}'s dev bootstrap, or a test's mock {@code
     * UserDetails} that isn't really an {@link AppUserPrincipal} at all — so the greeting (PRD
     * §24.2) falls back to a name-free "Welcome!" instead of failing. The same account has no
     * balances to show either, for the same reason.
     */
    @GetMapping("/")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String home(@Nullable @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        Optional<Employee> employee =
                principal == null ? Optional.empty() : employees.findByUserId(principal.userId());
        model.addAttribute("firstName", employee.map(Employee::firstName).orElse(null));
        model.addAttribute(
                "leaveBalances",
                employee.map(e -> balanceCards(e.requireId())).orElse(List.of()));
        return "home";
    }

    private List<BalanceCard> balanceCards(UUID employeeId) {
        return balances.listCurrentYearForEmployee(employeeId).stream()
                .map(
                        (LeaveBalance b) ->
                                new BalanceCard(
                                        b.leaveType().name(),
                                        b.leaveType().icon(),
                                        b.leaveType().color(),
                                        b.granted(),
                                        b.used(),
                                        b.remaining()))
                .toList();
    }

    record BalanceCard(
            String leaveTypeName,
            @Nullable String icon,
            @Nullable String color,
            BigDecimal granted,
            BigDecimal used,
            BigDecimal remaining) {}
}
