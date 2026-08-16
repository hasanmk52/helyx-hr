package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;

/**
 * A single day on the tenant's public holiday calendar (PRD §12.1, §12.5), added one at a time or
 * via CSV bulk upload. No edit — delete and re-add covers corrections, matching the "calendar
 * data, not a document" simplicity CURRENT_PHASE.md calls for.
 */
@Entity
@Table(
        name = "public_holiday",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "public_holiday_tenant_id_date_name_key",
                        columnNames = {"tenant_id", "date", "name"}))
public class PublicHoliday extends TenantAwareEntity {

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    protected PublicHoliday() {}

    private PublicHoliday(LocalDate date, String name) {
        this.date = date;
        this.name = name;
    }

    public static PublicHoliday create(LocalDate date, String name) {
        return new PublicHoliday(date, name);
    }

    public LocalDate date() {
        return date;
    }

    public String name() {
        return name;
    }
}
