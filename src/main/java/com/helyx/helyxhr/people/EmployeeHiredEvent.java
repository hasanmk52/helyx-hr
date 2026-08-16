package com.helyx.helyxhr.people;

import java.util.UUID;

/**
 * Published after a new {@link Employee} is created (PRD §14.2). An event rather than a direct
 * call for the same reason as {@link com.helyx.helyxhr.identity.UserInviteAcceptedEvent}: {@code
 * timeoff} needs to react to hires (initial balance grant, PRD §12.2), but {@code timeoff} also
 * needs to read {@code people} data for its own scheduled jobs via {@code PeopleFacade} — a
 * direct call in this direction too would be a package cycle. {@code people} does not know {@code
 * timeoff} exists.
 */
public record EmployeeHiredEvent(UUID employeeId) {}
