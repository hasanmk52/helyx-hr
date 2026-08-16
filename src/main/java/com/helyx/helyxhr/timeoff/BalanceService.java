package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.common.NotFoundException;
import com.helyx.helyxhr.people.PeopleFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grants pro-rated leave balances (PRD §12.2) via one core method shared by all three triggers —
 * on hire, on a leave type's (re)activation, and the annual Jan 1 job — and an idempotency check
 * backed by {@code leave_balance}'s own {@code UNIQUE (tenant_id, employee_id, leave_type_id,
 * year)} constraint (CLAUDE.md §8: "critical logic, TDD first").
 */
@Service
public class BalanceService {

    private static final Logger log = LoggerFactory.getLogger(BalanceService.class);
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final LeaveBalanceRepository balances;
    private final LeaveTypeRepository leaveTypes;
    private final PeopleFacade people;
    private final Clock clock;

    BalanceService(
            LeaveBalanceRepository balances,
            LeaveTypeRepository leaveTypes,
            PeopleFacade people,
            Clock clock) {
        this.balances = balances;
        this.leaveTypes = leaveTypes;
        this.people = people;
        this.clock = clock;
    }

    /** {@code web.HomeController}'s "Book Time Off" cards — the current year, by the app clock. */
    @Transactional(readOnly = true)
    public List<LeaveBalance> listCurrentYearForEmployee(UUID employeeId) {
        return balances.findAllByEmployeeIdAndYear(employeeId, LocalDate.now(clock).getYear());
    }

    /**
     * Called by {@link EmployeeHiredEventListener} after a new {@code Employee} is created (same
     * transaction — see {@code people.EmployeeService.create}'s doc comment). Grants every active
     * leave type, pro-rated from the employee's hire date. A missing hire date is a real,
     * PRD-allowed state (the field is optional on {@code Employee}), not an error — it just means
     * there is nothing to pro-rate from yet, so this no-ops until an Admin sets one.
     *
     * <p>A hire date in a past year — an existing employee entered with their real historical
     * hire date, e.g. an HRIS migration, not PRD §12.2's "new hire" case — grants a full balance
     * for the <em>current</em> year instead. A grant keyed to a year that has already ended would
     * never be seen: {@link #listCurrentYearForEmployee} and the annual job both only ever look
     * at the current year, so the employee would silently show no balance at all.
     */
    @Transactional
    public void grantOnHire(UUID employeeId) {
        PeopleFacade.EmployeeHireInfo info = people.requireEmployeeHireInfo(employeeId);
        LocalDate hireDate = info.hireDate();
        if (hireDate == null) {
            log.info("Skipping on-hire leave grant for employee {}: no hire date set", employeeId);
            return;
        }
        LocalDate today = LocalDate.now(clock);
        boolean historicalHire = hireDate.getYear() < today.getYear();
        int year = historicalHire ? today.getYear() : hireDate.getYear();
        LocalDate effectiveFrom = historicalHire ? LocalDate.of(year, 1, 1) : hireDate;
        for (LeaveType type : leaveTypes.findAllByActiveTrue()) {
            grantProRated(employeeId, type, year, effectiveFrom);
        }
    }

    /**
     * Backfills every existing active employee when a leave type is created or reactivated (PRD
     * §12.2's "initial grant on activation"), pro-rated from today.
     */
    @Transactional
    public void grantOnLeaveTypeActivation(LeaveType leaveType) {
        LocalDate today = LocalDate.now(clock);
        for (PeopleFacade.EmployeeHireInfo info : people.listActiveEmployeeHireInfo()) {
            grantProRated(info.employeeId(), leaveType, today.getYear(), today);
        }
    }

    /**
     * Fires Jan 1 (via {@link AnnualGrantJob}, one call per tenant). Jan 1 as the effective date
     * naturally yields a full, non-pro-rated grant through the same formula {@link #grantOnHire}
     * uses — no special-casing needed.
     *
     * @return the number of balance rows actually created, so the caller/job can log a
     *     meaningful count and tests can assert idempotency (a second call for the same year
     *     returns zero).
     */
    @Transactional
    public int grantAnnual(int year) {
        LocalDate jan1 = LocalDate.of(year, 1, 1);
        var activeTypes = leaveTypes.findAllByActiveTrue();
        int grantedCount = 0;
        for (PeopleFacade.EmployeeHireInfo info : people.listActiveEmployeeHireInfo()) {
            for (LeaveType type : activeTypes) {
                if (grantProRated(info.employeeId(), type, year, jan1)) {
                    grantedCount++;
                }
            }
        }
        return grantedCount;
    }

    /**
     * Admin manual adjustment (PRD §12.2), required-reason. No {@code audit_entry} table exists
     * yet (Phase 1.11) — logged via SLF4J in the meantime, same precedent as {@code
     * people.EmployeeService}/{@code InviteService}.
     */
    @Transactional
    public LeaveBalance adjustManually(
            UUID employeeId, UUID leaveTypeId, BigDecimal newGranted, String reason, String actorEmail) {
        int year = LocalDate.now(clock).getYear();
        LeaveBalance balance =
                balances
                        .findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "LEAVE_BALANCE_NOT_FOUND",
                                                "No " + year + " balance found for this employee/leave type"));
        BigDecimal previous = balance.granted();
        balance.adjustGranted(newGranted);
        log.info(
                "Manually adjusted leave balance: employee={} leaveType={} year={} {} -> {} reason=\"{}\" actor={}",
                employeeId,
                leaveTypeId,
                year,
                previous,
                newGranted,
                reason,
                actorEmail);
        return balance;
    }

    /** @return true if a new row was created; false if one already existed (idempotent no-op). */
    private boolean grantProRated(UUID employeeId, LeaveType leaveType, int year, LocalDate effectiveFrom) {
        UUID leaveTypeId = leaveType.requireId();
        if (balances.existsByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year)) {
            return false;
        }
        BigDecimal amount = proRate(leaveType.defaultAnnualAllowance(), effectiveFrom);
        balances.save(LeaveBalance.grant(employeeId, leaveType, year, amount));
        return true;
    }

    /**
     * PRD §12.2: {@code annualAllowance * (monthsRemainingInYear / 12)}, rounded to the nearest
     * 0.5 (the worked example: hired July 1 with a 30-day annual allowance -> 15 days). "Months
     * remaining" counts the effective month itself, so Jan 1 -> 12 (a full grant) and Dec 31 -> 1.
     */
    static BigDecimal proRate(BigDecimal annualAllowance, LocalDate effectiveFrom) {
        int monthsRemaining = 13 - effectiveFrom.getMonthValue();
        BigDecimal raw =
                annualAllowance
                        .multiply(BigDecimal.valueOf(monthsRemaining))
                        .divide(TWELVE, 4, RoundingMode.HALF_UP);
        return raw.multiply(TWO).setScale(0, RoundingMode.HALF_UP).divide(TWO).setScale(2, RoundingMode.HALF_UP);
    }
}
