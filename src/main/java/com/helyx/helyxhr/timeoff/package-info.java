/**
 * Leave policy configuration and balances: leave types, the public holiday calendar, and
 * per-employee-per-year balances (PRD §12, §21). Leave requests and the duration algorithm are
 * Phase 1.6, not here.
 *
 * <p>Every entity here is tenant-scoped (CLAUDE.md §5). Reads of {@code people}'s Employee data go
 * through {@code people.PeopleFacade} — {@code people} never depends back on {@code timeoff};
 * {@code timeoff} reacts to employee creation via {@code people.EmployeeHiredEvent} instead of a
 * direct call, keeping the two domain packages acyclic (see {@code EmployeeHiredEventListener}).
 */
@NullMarked
package com.helyx.helyxhr.timeoff;

import org.jspecify.annotations.NullMarked;
