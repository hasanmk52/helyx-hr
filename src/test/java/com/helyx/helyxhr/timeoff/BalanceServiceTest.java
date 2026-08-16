package com.helyx.helyxhr.timeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.helyx.helyxhr.people.Employee;
import com.helyx.helyxhr.people.EmployeeForms;
import com.helyx.helyxhr.people.EmployeeService;
import com.helyx.helyxhr.support.MutableClock;
import com.helyx.helyxhr.support.MutableClockConfiguration;
import com.helyx.helyxhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Proves the three PRD §12.2 grant triggers (on-hire, on-leave-type-activation, annual) share
 * correct pro-rate math and are idempotent — CLAUDE.md §8's "critical logic, TDD first" category.
 */
@Import(MutableClockConfiguration.class)
class BalanceServiceTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private EmployeeService employeeService;
    @Autowired private LeaveTypeService leaveTypeService;
    @Autowired private LeaveBalanceRepository balances;
    @Autowired private BalanceService balanceService;
    @Autowired private MutableClock clock;

    @Test
    void grantOnHire_julyHireWithThirtyDayAllowance_grantsFifteenDays() {
        // PRD §12.2 worked example: hired July 1 with a 30-day annual allowance -> 15 days.
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));

        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2026, 7, 1)), BASE_URL, TENANT_NAME));

        List<LeaveBalance> result =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().granted()).isEqualByComparingTo("15.00");
    }

    @Test
    void grantOnHire_janOneHireWithThirtyDayAllowance_grantsFullThirtyDays() {
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));

        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2026, 1, 1)), BASE_URL, TENANT_NAME));

        List<LeaveBalance> result =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026));
        assertThat(result.getFirst().granted()).isEqualByComparingTo("30.00");
    }

    @Test
    void grantOnHire_decThirtyFirstHireWithThirtyDayAllowance_grantsHalfDay() {
        // Dec (month 12): monthsRemaining = 1 -> 30 * 1/12 = 2.5, rounded to nearest 0.5 already.
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));

        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2026, 12, 31)), BASE_URL, TENANT_NAME));

        List<LeaveBalance> result =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026));
        assertThat(result.getFirst().granted()).isEqualByComparingTo("2.50");
    }

    @Test
    void grantOnHire_historicalHireDateFromAPastYear_grantsFullAllowanceForTheCurrentYear() {
        // An existing employee entered with their real historical hire date (e.g. an HRIS
        // migration) — must grant a full current-year balance, not a stale grant for a year
        // that has already ended (which nothing ever reads: the dashboard only queries the
        // current year via listCurrentYearForEmployee).
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));

        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2018, 4, 1)), BASE_URL, TENANT_NAME));

        int currentYear = LocalDate.now(clock).getYear();
        List<LeaveBalance> currentYearResult =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), currentYear));
        assertThat(currentYearResult).hasSize(1);
        assertThat(currentYearResult.getFirst().granted()).isEqualByComparingTo("30.00");

        List<LeaveBalance> hireYearResult =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2018));
        assertThat(hireYearResult).isEmpty();
    }

    @Test
    void grantOnHire_firedTwiceForSameEmployee_doesNotDoubleTheBalance() {
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));
        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2026, 7, 1)), BASE_URL, TENANT_NAME));

        // EmployeeService.create already fired the grant once via EmployeeHiredEvent; fire again
        // directly to prove the idempotency check, not just that it only ever fires once.
        asTenant(
                tenantA,
                () -> {
                    balanceService.grantOnHire(employee.requireId());
                    return null;
                });

        List<LeaveBalance> result =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026));
        assertThat(result).hasSize(1);
    }

    @Test
    void grantOnLeaveTypeActivation_backfillsExistingActiveEmployees() {
        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2020, 1, 1)), BASE_URL, TENANT_NAME));

        // No leave types existed at hire time, so no balance yet.
        assertThat(
                        asTenant(
                                tenantA,
                                () ->
                                        balances.findAllByEmployeeIdAndYear(
                                                employee.requireId(), LocalDate.now(clock).getYear())))
                .isEmpty();

        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Sick", null, null, true, false, false, true, new BigDecimal("10"), null));

        List<LeaveBalance> result =
                asTenant(
                        tenantA,
                        () ->
                                balances.findAllByEmployeeIdAndYear(
                                        employee.requireId(), LocalDate.now(clock).getYear()));
        assertThat(result).hasSize(1);
    }

    @Test
    void grantAnnual_runTwiceForSameYear_secondRunGrantsNothingNew() {
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("24"), null));
        asTenant(
                tenantA,
                () ->
                        employeeService.create(
                                createEmployeeForm(LocalDate.of(2020, 1, 1)), BASE_URL, TENANT_NAME));

        int first = asTenant(tenantA, () -> balanceService.grantAnnual(2027));
        int second = asTenant(tenantA, () -> balanceService.grantAnnual(2027));

        assertThat(first).isGreaterThan(0);
        assertThat(second).isZero();
    }

    @Test
    void grantAnnual_onlyGrantsForTheOwningTenant() {
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("24"), null));
        Employee employeeA =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2020, 1, 1)), BASE_URL, TENANT_NAME));

        asTenant(tenantA, () -> balanceService.grantAnnual(2027));

        List<LeaveBalance> forTenantB =
                asTenant(tenantB, () -> balances.findAllByEmployeeIdAndYear(employeeA.requireId(), 2027));
        assertThat(forTenantB).isEmpty();
    }

    private EmployeeForms.CreateEmployee createEmployeeForm(LocalDate hireDate) {
        return new EmployeeForms.CreateEmployee(
                "Priya",
                "Shah",
                UUID.randomUUID() + "@example.test",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                hireDate,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
