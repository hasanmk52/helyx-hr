package com.helyx.helyxhr.timeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.helyx.helyxhr.people.Employee;
import com.helyx.helyxhr.people.EmployeeForms;
import com.helyx.helyxhr.people.EmployeeService;
import com.helyx.helyxhr.support.MutableClock;
import com.helyx.helyxhr.support.MutableClockConfiguration;
import com.helyx.helyxhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Proves {@link AnnualGrantJob} fans out over real tenant contexts (not {@code runAsSystem},
 * which reads zero rows for RLS-protected tables — see the job's own Javadoc and ADR 0003), and
 * that running it twice for the same year does not double-grant (PRD §12.2, CLAUDE.md §8).
 *
 * <p>{@code @DirtiesContext}: this is the one test in the suite that advances {@link
 * MutableClock} across a year boundary (into 2027). {@code MutableClockConfiguration}'s bean is
 * a shared singleton in the cached Spring context that other {@code @Import(MutableClockConfiguration.class)}
 * test classes reuse — without this, the advanced clock leaks into whichever test class Surefire
 * happens to run next in the same JVM fork, silently changing what "today" means for any test
 * that reads {@code LocalDate.now(clock)} (e.g. {@code BalanceServiceTest}'s {@code grantOnHire}
 * assertions, which assume the year is still 2026).
 */
@Import(MutableClockConfiguration.class)
@DirtiesContext
class AnnualGrantJobTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private EmployeeService employeeService;
    @Autowired private LeaveTypeService leaveTypeService;
    @Autowired private LeaveBalanceRepository balances;
    @Autowired private AnnualGrantJob job;
    @Autowired private MutableClock clock;

    @Test
    void runAnnualGrant_atJanFirst_grantsForEveryTenantAndIsIdempotentOnRerun() {
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("24"), null));
        asTenant(
                tenantB,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("12"), null));
        Employee employeeA =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2020, 1, 1)), BASE_URL, TENANT_NAME));
        Employee employeeB =
                asTenant(
                        tenantB,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2020, 1, 1)), BASE_URL, TENANT_NAME));

        Instant jan2027 = LocalDate.of(2027, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        clock.advance(Duration.between(clock.instant(), jan2027));

        int firstRunTotal = job.runAnnualGrant();
        assertThat(firstRunTotal).isGreaterThanOrEqualTo(2);

        List<LeaveBalance> balancesA =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employeeA.requireId(), 2027));
        List<LeaveBalance> balancesB =
                asTenant(tenantB, () -> balances.findAllByEmployeeIdAndYear(employeeB.requireId(), 2027));
        assertThat(balancesA).hasSize(1);
        assertThat(balancesA.getFirst().granted()).isEqualByComparingTo("24.00");
        assertThat(balancesB).hasSize(1);
        assertThat(balancesB.getFirst().granted()).isEqualByComparingTo("12.00");

        int secondRunTotal = job.runAnnualGrant();
        assertThat(secondRunTotal).isZero();
        assertThat(asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employeeA.requireId(), 2027)))
                .hasSize(1);
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
