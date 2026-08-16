package com.helyx.helyxhr.timeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.helyx.helyxhr.people.Employee;
import com.helyx.helyxhr.people.EmployeeForms;
import com.helyx.helyxhr.people.EmployeeService;
import com.helyx.helyxhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CLAUDE.md §5 rule 8: every new tenant-scoped entity gets a test proving cross-tenant reads come
 * back empty. Covers all three Phase 1.5 tables (mirrors {@code PeopleTenantIsolationTest}'s
 * scope — proving the mechanism, not repeating a raw-JDBC probe per table).
 */
class TimeoffTenantIsolationTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private LeaveTypeRepository leaveTypes;
    @Autowired private PublicHolidayRepository holidays;
    @Autowired private LeaveBalanceRepository balances;
    @Autowired private EmployeeService employeeService;
    @Autowired private PostgreSQLContainer postgres;

    @Test
    void leaveType_seededInTenantA_isInvisibleToTenantB() {
        LeaveType saved =
                asTenant(
                        tenantA,
                        () ->
                                leaveTypes.save(
                                        LeaveType.create(
                                                "Annual",
                                                null,
                                                null,
                                                true,
                                                true,
                                                false,
                                                true,
                                                new BigDecimal("30"),
                                                null)));

        assertThat(asTenant(tenantA, () -> leaveTypes.findAll())).extracting("name").contains("Annual");
        assertThat(asTenant(tenantB, () -> leaveTypes.findById(saved.requireId()))).isEmpty();
    }

    @Test
    void publicHoliday_seededInTenantA_isInvisibleToTenantB() {
        PublicHoliday saved =
                asTenant(tenantA, () -> holidays.save(PublicHoliday.create(LocalDate.of(2026, 1, 1), "New Year")));

        assertThat(asTenant(tenantA, () -> holidays.findAllByOrderByDateAsc())).hasSize(1);
        assertThat(asTenant(tenantB, () -> holidays.findById(saved.requireId()))).isEmpty();
    }

    @Test
    void leaveBalance_seededInTenantA_isInvisibleToTenantB() {
        LeaveType type =
                asTenant(
                        tenantA,
                        () ->
                                leaveTypes.save(
                                        LeaveType.create(
                                                "Annual",
                                                null,
                                                null,
                                                true,
                                                true,
                                                false,
                                                true,
                                                new BigDecimal("30"),
                                                null)));
        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        new EmployeeForms.CreateEmployee(
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
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null),
                                        BASE_URL,
                                        TENANT_NAME));
        LeaveBalance saved =
                asTenant(
                        tenantA,
                        () ->
                                balances.save(
                                        LeaveBalance.grant(employee.requireId(), type, 2026, new BigDecimal("30"))));

        assertThat(asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026)))
                .hasSize(1);
        assertThat(asTenant(tenantB, () -> balances.findById(saved.requireId()))).isEmpty();
        assertThat(asTenant(tenantB, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026)))
                .isEmpty();
    }

    @Test
    void rawJdbc_withoutTenantSetting_rlsReturnsNoLeaveTypes() throws Exception {
        asTenant(
                tenantA,
                () ->
                        leaveTypes.save(
                                LeaveType.create(
                                        "Sick", null, null, true, false, false, true, new BigDecimal("10"), null)));

        assertThat(selectLeaveTypeNamesAsRestrictedRole(null)).isEmpty();
        assertThat(selectLeaveTypeNamesAsRestrictedRole(tenantA)).contains("Sick");
    }

    private List<String> selectLeaveTypeNamesAsRestrictedRole(UUID tenantId) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection connection =
                DriverManager.getConnection(postgres.getJdbcUrl(), "rls_probe", "rls_probe")) {
            connection.setAutoCommit(false);
            try {
                if (tenantId != null) {
                    try (PreparedStatement ps =
                            connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                        ps.setString(1, tenantId.toString());
                        ps.execute();
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement("SELECT name FROM helyx_hr.leave_type");
                        ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        return names;
    }
}
