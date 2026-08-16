package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave type CRUD (PRD §12.1, §12.5). No delete/archive: PRD §12.1 models this as a plain {@code
 * active} flag (see {@link LeaveType}'s doc comment) — simpler than {@code org.DivisionService}'s
 * delete-or-archive rule, since nothing needs an "is this referenced" guard.
 */
@Service
public class LeaveTypeService {

    private static final Logger log = LoggerFactory.getLogger(LeaveTypeService.class);

    private final LeaveTypeRepository leaveTypes;
    private final BalanceService balances;

    LeaveTypeService(LeaveTypeRepository leaveTypes, BalanceService balances) {
        this.leaveTypes = leaveTypes;
        this.balances = balances;
    }

    @Transactional(readOnly = true)
    public List<LeaveType> listAll() {
        return leaveTypes.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public LeaveType require(UUID id) {
        return leaveTypes
                .findById(id)
                .orElseThrow(() -> new NotFoundException("LEAVE_TYPE_NOT_FOUND", "Leave type not found"));
    }

    /**
     * Created active by construction, which is itself an activation (PRD §12.2's "initial grant
     * on activation") — every existing employee gets a backfilled balance for the new type.
     */
    @Transactional
    public LeaveType create(
            String name,
            @Nullable String icon,
            @Nullable String color,
            boolean paid,
            boolean allowsHalfDay,
            boolean allowsBackdated,
            boolean requiresApproval,
            BigDecimal defaultAnnualAllowance,
            @Nullable String description) {
        requireNameAvailable(name, null);
        LeaveType saved =
                leaveTypes.save(
                        LeaveType.create(
                                name,
                                icon,
                                color,
                                paid,
                                allowsHalfDay,
                                allowsBackdated,
                                requiresApproval,
                                defaultAnnualAllowance,
                                description));
        log.info("Created leave type {}", saved.requireId());
        balances.grantOnLeaveTypeActivation(saved);
        return saved;
    }

    @Transactional
    public LeaveType edit(
            UUID id,
            String name,
            @Nullable String icon,
            @Nullable String color,
            boolean paid,
            boolean allowsHalfDay,
            boolean allowsBackdated,
            boolean requiresApproval,
            BigDecimal defaultAnnualAllowance,
            @Nullable String description) {
        LeaveType leaveType = require(id);
        requireNameAvailable(name, id);
        leaveType.update(
                name,
                icon,
                color,
                paid,
                allowsHalfDay,
                allowsBackdated,
                requiresApproval,
                defaultAnnualAllowance,
                description);
        return leaveType;
    }

    /** Re-activating a previously deactivated type also backfills — same rule as {@link #create}. */
    @Transactional
    public LeaveType activate(UUID id) {
        LeaveType leaveType = require(id);
        leaveType.activate();
        log.info("Activated leave type {}", id);
        balances.grantOnLeaveTypeActivation(leaveType);
        return leaveType;
    }

    @Transactional
    public LeaveType deactivate(UUID id) {
        LeaveType leaveType = require(id);
        leaveType.deactivate();
        log.info("Deactivated leave type {}", id);
        return leaveType;
    }

    /**
     * Combined write-then-read for {@code AdminLeaveController}'s mutation endpoints (ADR 0007):
     * a controller composing a write call and a separate read call as two independent
     * transactions is the exact shape that ADR reproduced as broken (a genuinely-committed write
     * rendering as an empty table in the same response) — so every controller-facing mutation
     * here returns the fresh list itself, from inside its own transaction, rather than making the
     * controller fetch it separately.
     */
    @Transactional
    public List<LeaveType> createAndList(
            String name,
            @Nullable String icon,
            @Nullable String color,
            boolean paid,
            boolean allowsHalfDay,
            boolean allowsBackdated,
            boolean requiresApproval,
            BigDecimal defaultAnnualAllowance,
            @Nullable String description) {
        create(name, icon, color, paid, allowsHalfDay, allowsBackdated, requiresApproval, defaultAnnualAllowance, description);
        return listAll();
    }

    @Transactional
    public List<LeaveType> editAndList(
            UUID id,
            String name,
            @Nullable String icon,
            @Nullable String color,
            boolean paid,
            boolean allowsHalfDay,
            boolean allowsBackdated,
            boolean requiresApproval,
            BigDecimal defaultAnnualAllowance,
            @Nullable String description) {
        edit(id, name, icon, color, paid, allowsHalfDay, allowsBackdated, requiresApproval, defaultAnnualAllowance, description);
        return listAll();
    }

    @Transactional
    public List<LeaveType> activateAndList(UUID id) {
        activate(id);
        return listAll();
    }

    @Transactional
    public List<LeaveType> deactivateAndList(UUID id) {
        deactivate(id);
        return listAll();
    }

    private void requireNameAvailable(String name, @Nullable UUID excludingId) {
        boolean taken =
                excludingId == null
                        ? leaveTypes.existsByNameIgnoreCase(name)
                        : leaveTypes.existsByNameIgnoreCaseAndIdNot(name, excludingId);
        if (taken) {
            throw new ConflictException(
                    "LEAVE_TYPE_NAME_TAKEN", "A leave type named \"" + name + "\" already exists");
        }
    }
}
