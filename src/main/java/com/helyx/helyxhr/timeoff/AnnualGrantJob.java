package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantFacade;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the annual leave balance grant (PRD §12.2) every Jan 1. Mirrors {@code
 * people.EmployeeTerminationJob}'s shape (externalized cron, a disable flag tests can flip, a
 * public method tests call directly at a known moment) including its one addition: {@code
 * leave_balance}/{@code employee} are RLS-protected per tenant, so {@code
 * TenantContext.runAsSystem} alone would read zero rows (ADR 0003 — system mode is not an RLS
 * bypass). This job fans out over {@link TenantFacade#listActiveTenantIds()} and sets a real,
 * resolved tenant context for each one in turn.
 */
@Component
public class AnnualGrantJob {

    private static final Logger log = LoggerFactory.getLogger(AnnualGrantJob.class);

    private final BalanceService balances;
    private final TenantFacade tenants;
    private final Clock clock;
    private final boolean enabled;

    AnnualGrantJob(
            BalanceService balances,
            TenantFacade tenants,
            Clock clock,
            @Value("${helyx.timeoff.annual-grant-job.enabled:true}") boolean enabled) {
        this.balances = balances;
        this.tenants = tenants;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${helyx.timeoff.annual-grant-job.cron:0 0 0 1 1 *}")
    void scheduledRun() {
        if (!enabled) {
            return;
        }
        runAnnualGrant();
    }

    public int runAnnualGrant() {
        int year = LocalDate.now(clock).getYear();
        int total = 0;
        for (UUID tenantId : tenants.listActiveTenantIds()) {
            TenantContext.set(tenantId);
            try {
                int granted = balances.grantAnnual(year);
                total += granted;
                if (granted > 0) {
                    log.info("Granted {} leave balance(s) for {} in tenant {}", granted, year, tenantId);
                }
            } finally {
                TenantContext.clear();
            }
        }
        return total;
    }
}
