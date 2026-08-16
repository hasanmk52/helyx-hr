package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * A tenant-configurable category of leave (PRD §12.1, §12.5) — e.g. "Annual", "Sick", "Unpaid".
 * Tenant-wide only: the PRD's data model has no department scoping for this entity.
 *
 * <p>No public setters (CLAUDE.md §10): every mutation is a named transition. There is no
 * archived state or hard delete (unlike {@code org.Division}/{@code Department}) — PRD §12.1
 * models this as a plain {@code active} flag, and existing {@code leave_balance} rows keep
 * referencing a deactivated type regardless.
 */
@Entity
@Table(
        name = "leave_type",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "leave_type_tenant_id_name_key",
                        columnNames = {"tenant_id", "name"}))
public class LeaveType extends TenantAwareEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "icon", length = 50)
    private @Nullable String icon;

    @Column(name = "color", length = 7)
    private @Nullable String color;

    @Column(name = "paid", nullable = false)
    private boolean paid;

    @Column(name = "allows_half_day", nullable = false)
    private boolean allowsHalfDay;

    @Column(name = "allows_backdated", nullable = false)
    private boolean allowsBackdated;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    @Column(name = "default_annual_allowance", nullable = false)
    private BigDecimal defaultAnnualAllowance;

    @Column(name = "description")
    private @Nullable String description;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected LeaveType() {}

    private LeaveType(
            String name,
            @Nullable String icon,
            @Nullable String color,
            boolean paid,
            boolean allowsHalfDay,
            boolean allowsBackdated,
            boolean requiresApproval,
            BigDecimal defaultAnnualAllowance,
            @Nullable String description) {
        this.name = name;
        this.icon = icon;
        this.color = color;
        this.paid = paid;
        this.allowsHalfDay = allowsHalfDay;
        this.allowsBackdated = allowsBackdated;
        this.requiresApproval = requiresApproval;
        this.defaultAnnualAllowance = defaultAnnualAllowance;
        this.description = description;
        this.active = true;
    }

    public static LeaveType create(
            String name,
            @Nullable String icon,
            @Nullable String color,
            boolean paid,
            boolean allowsHalfDay,
            boolean allowsBackdated,
            boolean requiresApproval,
            BigDecimal defaultAnnualAllowance,
            @Nullable String description) {
        return new LeaveType(
                name,
                icon,
                color,
                paid,
                allowsHalfDay,
                allowsBackdated,
                requiresApproval,
                defaultAnnualAllowance,
                description);
    }

    public void update(
            String name,
            @Nullable String icon,
            @Nullable String color,
            boolean paid,
            boolean allowsHalfDay,
            boolean allowsBackdated,
            boolean requiresApproval,
            BigDecimal defaultAnnualAllowance,
            @Nullable String description) {
        this.name = name;
        this.icon = icon;
        this.color = color;
        this.paid = paid;
        this.allowsHalfDay = allowsHalfDay;
        this.allowsBackdated = allowsBackdated;
        this.requiresApproval = requiresApproval;
        this.defaultAnnualAllowance = defaultAnnualAllowance;
        this.description = description;
    }

    public void activate() {
        active = true;
    }

    public void deactivate() {
        active = false;
    }

    public String name() {
        return name;
    }

    public @Nullable String icon() {
        return icon;
    }

    public @Nullable String color() {
        return color;
    }

    public boolean paid() {
        return paid;
    }

    public boolean allowsHalfDay() {
        return allowsHalfDay;
    }

    public boolean allowsBackdated() {
        return allowsBackdated;
    }

    public boolean requiresApproval() {
        return requiresApproval;
    }

    public BigDecimal defaultAnnualAllowance() {
        return defaultAnnualAllowance;
    }

    public @Nullable String description() {
        return description;
    }

    public boolean active() {
        return active;
    }
}
