package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.people.EmployeeHiredEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Grants a new employee's initial leave balances (PRD §12.2). Decoupled via an event, not a
 * direct call from {@code people}, so {@code people} never needs to know {@code timeoff} exists
 * (see {@link EmployeeHiredEvent}'s doc comment) — mirrors {@code
 * people.EmployeeInviteAcceptedListener}.
 *
 * <p>Plain {@code @EventListener}, not {@code @TransactionalEventListener(AFTER_COMMIT)} like
 * that listener: the initial grant is an internal DB write, not a side effect that should survive
 * an employee record failing to commit, so it stays inside {@code EmployeeService.create}'s own
 * transaction instead of opening a new one after it.
 */
@Component
class EmployeeHiredEventListener {

    private final BalanceService balances;

    EmployeeHiredEventListener(BalanceService balances) {
        this.balances = balances;
    }

    @EventListener
    void onEmployeeHired(EmployeeHiredEvent event) {
        balances.grantOnHire(event.employeeId());
    }
}
