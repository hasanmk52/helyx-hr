package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One employee's entitlement to one leave type for one calendar year (PRD §12.1, §12.2). {@code
 * used} stays zero this phase — booking/approval (Phase 1.6) is what ever increments it.
 *
 * <p>{@code employeeId} is a plain id, not a JPA relation: {@code timeoff} reads {@code people}
 * data only through {@code PeopleFacade} (CLAUDE.md §4), the same convention {@code
 * org.Department.headEmployeeId} already established for a cross-module reference. {@code
 * leaveType} stays a real {@code @ManyToOne} because both entities live in this package.
 */
@Entity
@Table(
        name = "leave_balance",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "leave_balance_tenant_id_employee_id_leave_type_id_year_key",
                        columnNames = {"tenant_id", "employee_id", "leave_type_id", "year"}))
public class LeaveBalance extends TenantAwareEntity {

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "granted", nullable = false)
    private BigDecimal granted;

    @Column(name = "used", nullable = false)
    private BigDecimal used;

    protected LeaveBalance() {}

    private LeaveBalance(UUID employeeId, LeaveType leaveType, int year, BigDecimal granted) {
        this.employeeId = employeeId;
        this.leaveType = leaveType;
        this.year = year;
        this.granted = granted;
        this.used = BigDecimal.ZERO;
    }

    public static LeaveBalance grant(UUID employeeId, LeaveType leaveType, int year, BigDecimal granted) {
        return new LeaveBalance(employeeId, leaveType, year, granted);
    }

    /**
     * Admin manual adjustment (PRD §12.2). The required reason is logged by the caller (
     * {@code BalanceService}), not stored on this row — the real {@code audit_entry} table lands
     * in Phase 1.11.
     */
    public void adjustGranted(BigDecimal newGranted) {
        this.granted = newGranted;
    }

    public UUID employeeId() {
        return employeeId;
    }

    public LeaveType leaveType() {
        return leaveType;
    }

    public int year() {
        return year;
    }

    public BigDecimal granted() {
        return granted;
    }

    public BigDecimal used() {
        return used;
    }

    public BigDecimal remaining() {
        return granted.subtract(used);
    }
}
