package com.helyx.helyxhr.people;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.NotFoundException;
import com.helyx.helyxhr.common.ValidationException;
import com.helyx.helyxhr.identity.AppUser;
import com.helyx.helyxhr.identity.AppUserPrincipal;
import com.helyx.helyxhr.identity.AppUserRepository;
import com.helyx.helyxhr.identity.InviteService;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.org.Department;
import com.helyx.helyxhr.org.OrgFacade;
import com.helyx.helyxhr.security.SessionRevoker;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee CRUD, lifecycle transitions, and view-authorization (PRD §14, §21). {@code @Transactional}
 * write-then-read methods here are what {@code AdminEmployeeController}/{@code ProfileController}
 * call instead of composing repository calls themselves (ADR 0007 — mirrors {@code
 * OrganizationAdminService}).
 */
@Service
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

    private final EmployeeRepository employees;
    private final EmployeeStatusHistoryRepository statusHistory;
    private final EmployeeManagerHistoryRepository managerHistory;
    private final EmergencyContactRepository emergencyContacts;
    private final GovernmentIdRepository governmentIds;
    private final BankDetailRepository bankDetails;
    private final EducationRepository educations;
    private final BenefitRepository benefits;
    private final InviteService inviteService;
    private final AppUserRepository appUsers;
    private final OrgFacade orgFacade;
    private final SessionRevoker sessionRevoker;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    EmployeeService(
            EmployeeRepository employees,
            EmployeeStatusHistoryRepository statusHistory,
            EmployeeManagerHistoryRepository managerHistory,
            EmergencyContactRepository emergencyContacts,
            GovernmentIdRepository governmentIds,
            BankDetailRepository bankDetails,
            EducationRepository educations,
            BenefitRepository benefits,
            InviteService inviteService,
            AppUserRepository appUsers,
            OrgFacade orgFacade,
            SessionRevoker sessionRevoker,
            Clock clock,
            ApplicationEventPublisher events) {
        this.employees = employees;
        this.statusHistory = statusHistory;
        this.managerHistory = managerHistory;
        this.emergencyContacts = emergencyContacts;
        this.governmentIds = governmentIds;
        this.bankDetails = bankDetails;
        this.educations = educations;
        this.benefits = benefits;
        this.inviteService = inviteService;
        this.appUsers = appUsers;
        this.orgFacade = orgFacade;
        this.sessionRevoker = sessionRevoker;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Employee> list(@Nullable UUID departmentId, @Nullable EmployeeStatus status) {
        if (departmentId != null && status != null) {
            return employees.findAllByDepartmentIdAndStatusOrderByLastNameAscFirstNameAsc(departmentId, status);
        }
        if (departmentId != null) {
            return employees.findAllByDepartmentIdOrderByLastNameAscFirstNameAsc(departmentId);
        }
        if (status != null) {
            return employees.findAllByStatusOrderByLastNameAscFirstNameAsc(status);
        }
        return employees.findAllByOrderByLastNameAscFirstNameAsc();
    }

    @Transactional(readOnly = true)
    public Employee require(UUID id) {
        return employees
                .findById(id)
                .orElseThrow(() -> new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"));
    }

    @Transactional(readOnly = true)
    public Optional<Employee> findByUserId(UUID userId) {
        return employees.findByUserId(userId);
    }

    /**
     * Creates an Employee in {@code INVITED} status and sends the login invite (reusing {@link
     * InviteService} — PRD §14.2). Write-then-read-back-via-invite in one transaction (ADR 0007).
     */
    @Transactional
    public Employee create(EmployeeForms.CreateEmployee form, String appBaseUrl, String tenantName) {
        if (employees.existsByEmailIgnoreCase(form.email())) {
            throw new ConflictException(
                    "EMPLOYEE_EMAIL_TAKEN", "An employee with that email already exists");
        }
        LocalDate today = LocalDate.now(clock);

        Employee employee = Employee.create(form.firstName(), form.lastName(), form.email());
        applyAdminFields(employee, form.toAdminPatch());
        employees.save(employee);

        statusHistory.save(EmployeeStatusHistory.open(employee, EmployeeStatus.INVITED, form.employmentType(), today));
        if (employee.manager() != null) {
            managerHistory.save(EmployeeManagerHistory.open(employee, employee.manager(), today));
        }

        UUID userId = inviteService.invite(form.email(), Set.of(Role.EMPLOYEE), appBaseUrl, tenantName);
        employee.linkUser(userId);

        // Published, not called directly: people must not depend on timeoff (see
        // EmployeeHiredEvent's doc comment). Plain @EventListener, not @TransactionalEventListener
        // AFTER_COMMIT like EmployeeInviteAcceptedListener — the initial balance grant is an
        // internal DB write with no reason to survive this employee record failing to commit, so
        // it belongs in the same transaction, not a REQUIRES_NEW one after it.
        events.publishEvent(new EmployeeHiredEvent(employee.requireId()));

        log.info("Created employee {}", employee.requireId());
        return employee;
    }

    /**
     * Called by {@link EmployeeInviteAcceptedListener} after the invite's AppUser activates.
     *
     * <p>{@code REQUIRES_NEW}, not the default propagation: this runs from an {@code
     * AFTER_COMMIT} transactional event listener, where Spring's transaction-synchronization
     * bookkeeping for the triggering transaction is still bound to the thread (cleanup hasn't run
     * yet) even though it has already physically committed. Default {@code REQUIRED} propagation
     * would join that finishing transaction instead of opening a real new one — {@link
     * com.helyx.helyxhr.tenant.TenantSessionVariableListener}'s {@code afterBegin} would never
     * fire, RLS's transaction-scoped {@code app.tenant_id} (set via {@code SET LOCAL}, which dies
     * at the original transaction's {@code COMMIT}) would read as unset, and {@code
     * findByUserId} below would silently see zero rows — exactly what happened before this was
     * forced to open its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void activateForUser(UUID userId) {
        employees
                .findByUserId(userId)
                .filter(employee -> employee.status() == EmployeeStatus.INVITED)
                .ifPresent(employee -> transitionStatus(employee, EmployeeStatus.ACTIVE));
    }

    @Transactional
    public Employee updateSelfServiceFields(UUID employeeId, EmployeeForms.SelfProfilePatch patch) {
        Employee employee = require(employeeId);
        employee.updateSelfServiceFields(
                patch.phone(),
                patch.addressLine1(),
                patch.addressLine2(),
                patch.city(),
                patch.country(),
                patch.postalCode());
        return employee;
    }

    @Transactional
    public Employee updateAdminFields(UUID employeeId, EmployeeForms.AdminEmployeePatch patch) {
        Employee employee = require(employeeId);
        if (employees.existsByEmailIgnoreCaseAndIdNot(patch.email(), employeeId)) {
            throw new ConflictException(
                    "EMPLOYEE_EMAIL_TAKEN", "An employee with that email already exists");
        }
        applyAdminFields(employee, patch);
        return employee;
    }

    private void applyAdminFields(Employee employee, EmployeeForms.AdminEmployeePatch patch) {
        Department department =
                patch.departmentId() == null ? null : orgFacade.requireActiveDepartment(patch.departmentId());
        employee.updateAdminFields(
                patch.employeeCode(),
                patch.firstName(),
                patch.lastName(),
                patch.email(),
                patch.phone(),
                patch.birthDate(),
                patch.gender(),
                patch.maritalStatus(),
                patch.nationality(),
                patch.citizenship(),
                patch.hireDate(),
                patch.employmentType(),
                department,
                patch.jobTitle(),
                patch.workLocation(),
                patch.workingHoursPerDay() == null ? BigDecimal.valueOf(8.0) : patch.workingHoursPerDay(),
                patch.currency(),
                patch.baseCompensation());
        if (patch.managerId() != null || employee.manager() != null) {
            UUID currentManagerId = employee.manager() == null ? null : employee.manager().requireId();
            if (!java.util.Objects.equals(currentManagerId, patch.managerId())) {
                reassignManagerInternal(employee, patch.managerId());
            }
        }
    }

    @Transactional
    public Employee changeStatus(UUID employeeId, EmployeeStatus status) {
        Employee employee = require(employeeId);
        transitionStatus(employee, status);
        return employee;
    }

    private void transitionStatus(Employee employee, EmployeeStatus status) {
        LocalDate today = LocalDate.now(clock);
        statusHistory
                .findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(employee.requireId())
                .ifPresent(open -> open.close(today));
        statusHistory.save(EmployeeStatusHistory.open(employee, status, employee.employmentType(), today));
        employee.changeStatus(status);
    }

    @Transactional
    public Employee reassignManager(UUID employeeId, @Nullable UUID managerId) {
        Employee employee = require(employeeId);
        reassignManagerInternal(employee, managerId);
        return employee;
    }

    private void reassignManagerInternal(Employee employee, @Nullable UUID managerId) {
        if (managerId != null && managerId.equals(employee.requireId())) {
            throw new ValidationException(
                    "EMPLOYEE_CANNOT_MANAGE_SELF", "An employee cannot be their own manager");
        }
        Employee manager = managerId == null ? null : require(managerId);
        LocalDate today = LocalDate.now(clock);
        managerHistory
                .findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(employee.requireId())
                .ifPresent(open -> open.close(today));
        managerHistory.save(EmployeeManagerHistory.open(employee, manager, today));
        employee.reassignManager(manager);
    }

    /**
     * Schedules (or immediately applies) termination (PRD §14.4). Past/today applies immediately,
     * including session revocation; future just records the date — {@link EmployeeTerminationJob}
     * applies it when the date arrives.
     */
    @Transactional
    public Employee terminate(UUID employeeId, LocalDate terminationDate) {
        Employee employee = require(employeeId);
        employee.scheduleTermination(terminationDate);
        if (!terminationDate.isAfter(LocalDate.now(clock))) {
            applyTermination(employee);
        }
        return employee;
    }

    /** {@link EmployeeTerminationJob}'s entry point — applies every termination whose date has arrived. */
    @Transactional
    public int applyDueTerminations(LocalDate today) {
        List<Employee> due =
                employees.findAllByTerminationDateLessThanEqualAndStatusNot(today, EmployeeStatus.TERMINATED);
        due.forEach(this::applyTermination);
        return due.size();
    }

    private void applyTermination(Employee employee) {
        transitionStatus(employee, EmployeeStatus.TERMINATED);
        if (employee.userId() != null) {
            // Both halves of "login blocked" (PRD §14.4): disable() stops any *future* login
            // attempt (AppUserDetailsService#isEnabled), revokeAllFor kills sessions already in
            // progress. Neither alone is sufficient — a disabled-but-not-revoked account stays
            // logged in until its session naturally expires; a revoked-but-not-disabled account
            // can just log back in again immediately.
            appUsers.findById(employee.userId()).ifPresent(AppUser::disable);
            sessionRevoker.revokeAllFor(employee.userId());
        }
        // Cancelling future leave requests (PRD §14.4) is a no-op until leave_request exists
        // (Phase 1.5/1.6) — reserved gap, not an oversight (see CURRENT_PHASE.md carried-forward).
        log.info("Terminated employee {}", employee.requireId());
    }

    /**
     * Self, Admin, or a direct/indirect manager may view a profile (PRD §26); anyone else is
     * denied. {@code AccessDeniedException} here is caught by the same {@code
     * ExceptionTranslationFilter} that handles {@code @PreAuthorize} failures, so it renders the
     * same 403 page (CLAUDE.md §6 A05).
     */
    @Transactional(readOnly = true)
    public Employee getProfileForViewer(UUID targetId, AppUserPrincipal viewer) {
        Employee target = require(targetId);
        if (viewer.roles().contains(Role.ADMIN)) {
            return target;
        }
        if (target.userId() != null && target.userId().equals(viewer.userId())) {
            return target;
        }
        if (viewer.roles().contains(Role.MANAGER)) {
            Optional<Employee> viewerEmployee = employees.findByUserId(viewer.userId());
            if (viewerEmployee.isPresent()
                    && employees.isManagerOf(viewerEmployee.get().requireId(), targetId)) {
                return target;
            }
        }
        throw new AccessDeniedException("Not authorized to view this profile");
    }

    // ---- Emergency contacts (self- or admin-editable; ownership enforced by the caller only
    // ever resolving employeeId to the caller's own record on the self-service path) ----

    @Transactional(readOnly = true)
    public List<EmergencyContact> listEmergencyContacts(UUID employeeId) {
        return emergencyContacts.findAllByEmployeeIdOrderByNameAsc(employeeId);
    }

    @Transactional
    public EmergencyContact addEmergencyContact(
            UUID employeeId,
            String name,
            @Nullable String relationship,
            @Nullable String phone,
            @Nullable String email) {
        Employee employee = require(employeeId);
        return emergencyContacts.save(EmergencyContact.create(employee, name, relationship, phone, email));
    }

    @Transactional
    public void removeEmergencyContact(UUID employeeId, UUID contactId) {
        EmergencyContact contact = requireOwnedBy(emergencyContacts.findById(contactId), employeeId, "EMERGENCY_CONTACT_NOT_FOUND");
        emergencyContacts.delete(contact);
    }

    // ---- Government IDs ----

    @Transactional(readOnly = true)
    public List<GovernmentId> listGovernmentIds(UUID employeeId) {
        return governmentIds.findAllByEmployeeIdOrderByIdTypeAsc(employeeId);
    }

    @Transactional
    public GovernmentId addGovernmentId(
            UUID employeeId,
            GovernmentIdType type,
            String idNumber,
            @Nullable String country,
            @Nullable LocalDate issueDate,
            @Nullable LocalDate expiryDate) {
        Employee employee = require(employeeId);
        return governmentIds.save(GovernmentId.create(employee, type, idNumber, country, issueDate, expiryDate));
    }

    @Transactional
    public void removeGovernmentId(UUID employeeId, UUID governmentIdId) {
        GovernmentId governmentId =
                requireOwnedBy(governmentIds.findById(governmentIdId), employeeId, "GOVERNMENT_ID_NOT_FOUND");
        governmentIds.delete(governmentId);
    }

    // ---- Bank detail (one row per employee) ----

    @Transactional(readOnly = true)
    public Optional<BankDetail> findBankDetail(UUID employeeId) {
        return bankDetails.findByEmployeeId(employeeId);
    }

    @Transactional
    public BankDetail upsertBankDetail(
            UUID employeeId,
            @Nullable String bankName,
            @Nullable String accountName,
            @Nullable String accountNumber,
            @Nullable String iban,
            @Nullable String swiftBic) {
        BankDetail bankDetail =
                bankDetails
                        .findByEmployeeId(employeeId)
                        .orElseGet(() -> BankDetail.create(require(employeeId)));
        bankDetail.update(bankName, accountName, accountNumber, iban, swiftBic);
        return bankDetails.save(bankDetail);
    }

    // ---- Education (Admin- or self-editable, same route-scoping rule as emergency contacts) ----

    @Transactional(readOnly = true)
    public List<Education> listEducation(UUID employeeId) {
        return educations.findAllByEmployeeIdOrderByStartYearDesc(employeeId);
    }

    @Transactional
    public Education addEducation(
            UUID employeeId,
            @Nullable String institution,
            @Nullable String degree,
            @Nullable String field,
            @Nullable Integer startYear,
            @Nullable Integer endYear) {
        Employee employee = require(employeeId);
        return educations.save(Education.create(employee, institution, degree, field, startYear, endYear));
    }

    @Transactional
    public void removeEducation(UUID employeeId, UUID educationId) {
        Education education = requireOwnedBy(educations.findById(educationId), employeeId, "EDUCATION_NOT_FOUND");
        educations.delete(education);
    }

    // ---- Benefits (Admin-managed) ----

    @Transactional(readOnly = true)
    public List<Benefit> listBenefits(UUID employeeId) {
        return benefits.findAllByEmployeeIdOrderByStartDateDesc(employeeId);
    }

    @Transactional
    public Benefit addBenefit(
            UUID employeeId,
            @Nullable String name,
            @Nullable String category,
            @Nullable LocalDate startDate,
            @Nullable LocalDate endDate) {
        Employee employee = require(employeeId);
        return benefits.save(Benefit.create(employee, name, category, startDate, endDate));
    }

    /**
     * Guards against IDOR: a sub-entity id that exists but belongs to a different employee reads
     * as not-found, same as a truly missing id — never leaks that the row exists elsewhere.
     */
    private static <T> T requireOwnedBy(
            Optional<T> candidate, UUID employeeId, String errorCode) {
        return candidate
                .filter(entry -> ownerId(entry).equals(employeeId))
                .orElseThrow(() -> new NotFoundException(errorCode, "Not found"));
    }

    private static UUID ownerId(Object entry) {
        return switch (entry) {
            case EmergencyContact contact -> contact.employee().requireId();
            case GovernmentId governmentId -> governmentId.employee().requireId();
            case Education education -> education.employee().requireId();
            default -> throw new IllegalArgumentException("Unsupported sub-entity: " + entry.getClass());
        };
    }
}
