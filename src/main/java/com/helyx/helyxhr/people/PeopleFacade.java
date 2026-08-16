package com.helyx.helyxhr.people;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of Employee data for other modules (CLAUDE.md §4). Original consumer is {@code
 * web.AdminOrganizationController}, which needs an employee count to decide whether deleting a
 * Department should archive it instead (PRD §6.4 FR-4.2, the guard 1.3 deferred). {@code timeoff}
 * is the second consumer (Phase 1.5) — its balance-grant jobs need to iterate active employees;
 * {@code timeoff} never depends back on {@code people} for anything else, and {@code people}
 * never imports {@code timeoff} at all (see {@link EmployeeHiredEvent}).
 *
 * <p>Deliberately not consumed by {@code org} itself: {@code org} already flows into {@code
 * people} via {@code OrgFacade}, so {@code org} depending back on {@code people} would be a
 * package cycle ArchUnit rejects. The orchestration lives one layer up instead.
 */
public interface PeopleFacade {

    /** Employees in this department whose status isn't TERMINATED. */
    long countActiveEmployeesInDepartment(UUID departmentId);

    /**
     * Employees whose status isn't TERMINATED, for {@code timeoff.BalanceService}'s annual grant
     * job and leave-type-activation backfill (PRD §12.2). {@code hireDate} is nullable exactly as
     * it is on {@link Employee} itself — callers must decide what to do with an employee who has
     * none.
     */
    List<EmployeeHireInfo> listActiveEmployeeHireInfo();

    /**
     * @throws com.helyx.helyxhr.common.NotFoundException if the employee doesn't exist. Backs the
     *     on-hire grant ({@link EmployeeHiredEvent}).
     */
    EmployeeHireInfo requireEmployeeHireInfo(UUID employeeId);

    record EmployeeHireInfo(UUID employeeId, @Nullable LocalDate hireDate) {}
}
