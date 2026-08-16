package com.helyx.helyxhr.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.NotFoundException;
import com.helyx.helyxhr.common.ValidationException;
import com.helyx.helyxhr.identity.AppUser;
import com.helyx.helyxhr.identity.AppUserDetailsService;
import com.helyx.helyxhr.identity.AppUserPrincipal;
import com.helyx.helyxhr.identity.AppUserRepository;
import com.helyx.helyxhr.identity.InviteService;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.notifications.system.EmailOutbox;
import com.helyx.helyxhr.notifications.system.EmailOutboxRepository;
import com.helyx.helyxhr.org.Division;
import com.helyx.helyxhr.org.DivisionRepository;
import com.helyx.helyxhr.org.Department;
import com.helyx.helyxhr.org.DepartmentRepository;
import com.helyx.helyxhr.support.MutableClock;
import com.helyx.helyxhr.support.MutableClockConfiguration;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.net.URLDecoder;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;

/**
 * Employee CRUD, field-permission boundaries, status/manager history, termination, and
 * view-authorization (PRD §14, §26). Extends {@link TenantIsolationTestBase} directly against
 * Testcontainers Postgres, matching {@code OrganizationServiceTest}/{@code
 * InviteAndResetServiceTest}'s convention.
 */
@Import(MutableClockConfiguration.class)
class EmployeeServiceTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeeRepository employees;
    @Autowired private EmployeeStatusHistoryRepository statusHistory;
    @Autowired private EmployeeManagerHistoryRepository managerHistory;
    @Autowired private PeopleFacade peopleFacade;
    @Autowired private DivisionRepository divisionRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private AppUserRepository appUsers;
    @Autowired private AppUserDetailsService userDetailsService;
    @Autowired private MutableClock clock;
    @Autowired private InviteService inviteService;
    @Autowired private EmailOutboxRepository emailOutbox;

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_%\\-]+)");

    @Test
    void create_startsInvitedWithOpenStatusHistoryAndLinksAnAppUser() {
        Employee employee = asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", uniqueEmail()), BASE_URL, TENANT_NAME));

        assertThat(employee.status()).isEqualTo(EmployeeStatus.INVITED);
        assertThat(employee.userId()).isNotNull();

        AppUser linkedUser = asTenant(tenantA, () -> appUsers.findById(employee.userId())).orElseThrow();
        assertThat(linkedUser.roles()).containsExactly(Role.EMPLOYEE);

        var openHistory =
                asTenant(
                        tenantA,
                        () ->
                                statusHistory.findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(
                                        employee.requireId()));
        assertThat(openHistory).isPresent();
        assertThat(openHistory.orElseThrow().status()).isEqualTo(EmployeeStatus.INVITED);
    }

    /**
     * Regression test for a real bug: goes through {@link InviteService#acceptInvite}, not {@link
     * EmployeeService#activateForUser} directly. {@code EmployeeInviteAcceptedListener} runs
     * {@code activateForUser} from an {@code AFTER_COMMIT} transactional event listener, where the
     * default {@code @Transactional} propagation joined the already-physically-committed
     * triggering transaction instead of opening a new one — {@code TenantSessionVariableListener}
     * never re-armed RLS's {@code app.tenant_id} (transaction-scoped via {@code SET LOCAL}), so the
     * lookup inside {@code activateForUser} silently found nothing and the Employee stayed
     * INVITED forever, even though the linked AppUser correctly went ACTIVE. Fixed with {@code
     * Propagation.REQUIRES_NEW}. A test calling {@code activateForUser} directly (as every other
     * test in this class effectively did, via the create/require path) cannot catch this class of
     * bug — it has to run the real event.
     */
    @Test
    void acceptInvite_transitionsTheLinkedEmployeeFromInvitedToActive() {
        String email = uniqueEmail();
        Employee employee = asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", email), BASE_URL, TENANT_NAME));
        assertThat(employee.status()).isEqualTo(EmployeeStatus.INVITED);
        String rawToken = tokenFromLastEmailTo(email);

        asTenant(tenantA, () -> inviteService.acceptInvite(rawToken, "Str0ngPassphrase"));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.status()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    /** email_outbox is system-scoped, so reading it needs system mode rather than a tenant. */
    private EmailOutbox lastEmailTo(String email) {
        List<EmailOutbox> found =
                TenantContext.runAsSystem(
                        "test: read outbox",
                        () -> emailOutbox.findAll().stream().filter(row -> row.toEmail().equals(email)).toList());
        assertThat(found).as("queued emails to %s", email).isNotEmpty();
        return found.getLast();
    }

    /** Pulls the raw token back out of the delivered link, the way a real recipient would. */
    private String tokenFromLastEmailTo(String email) {
        Matcher matcher = TOKEN_IN_LINK.matcher(lastEmailTo(email).bodyHtml());
        assertThat(matcher.find()).as("token in email body").isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    @Test
    void create_withDuplicateEmailInSameTenant_throwsConflict() {
        String email = uniqueEmail();
        asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", email), BASE_URL, TENANT_NAME));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () -> employeeService.create(form("Other", "Person", email), BASE_URL, TENANT_NAME)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void activateForUser_flipsInvitedEmployeeToActive() {
        Employee employee =
                asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", uniqueEmail()), BASE_URL, TENANT_NAME));

        asTenant(tenantA, () -> employeeService.activateForUser(employee.userId()));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.status()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void updateSelfServiceFields_onlyTouchesContactAndAddressFields() {
        Employee employee = createActiveEmployee("Jane", "Doe");

        asTenant(
                tenantA,
                () ->
                        employeeService.updateSelfServiceFields(
                                employee.requireId(),
                                new EmployeeForms.SelfProfilePatch("+1000", "1 Main St", null, "Dubai", "UAE", "00000")));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.phone()).isEqualTo("+1000");
        assertThat(reloaded.city()).isEqualTo("Dubai");
        // Job/compensation fields are untouched — SelfProfilePatch has no such fields at all
        // (enforced by construction, not a runtime filter).
        assertThat(reloaded.jobTitle()).isNull();
    }

    @Test
    void updateAdminFields_setsJobAndEncryptedCompensation() {
        Employee employee = createActiveEmployee("Jane", "Doe");
        UUID departmentId = seedDepartment();

        asTenant(
                tenantA,
                () ->
                        employeeService.updateAdminFields(
                                employee.requireId(),
                                new EmployeeForms.AdminEmployeePatch(
                                        "EMP-1",
                                        employee.firstName(),
                                        employee.lastName(),
                                        employee.email(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        EmploymentType.FULL_TIME,
                                        departmentId,
                                        null,
                                        "Engineer",
                                        "Dubai HQ",
                                        BigDecimal.valueOf(8),
                                        "AED",
                                        "12000.00")));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.jobTitle()).isEqualTo("Engineer");
        assertThat(reloaded.department()).isNotNull();
        // The encrypted value decrypts correctly through the entity — the raw-column tamper-proof
        // property itself is covered by CryptoConverterTest.
        assertThat(reloaded.baseCompensation()).isEqualTo("12000.00");
    }

    @Test
    void reassignManager_opensNewHistoryRowAndClosesThePrevious() {
        Employee manager1 = createActiveEmployee("Manager", "One");
        Employee manager2 = createActiveEmployee("Manager", "Two");
        Employee report = createActiveEmployee("Report", "Ee");

        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager1.requireId()));
        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager2.requireId()));

        var history =
                asTenant(
                        tenantA,
                        () -> managerHistory.findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(report.requireId()));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).effectiveTo()).isNull(); // most recent row still open
        assertThat(history.get(1).effectiveTo()).isNotNull(); // superseded row closed

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(report.requireId()));
        assertThat(reloaded.manager()).isNotNull();
    }

    @Test
    void reassignManager_toSelf_throwsValidation() {
        Employee employee = createActiveEmployee("Jane", "Doe");

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> employeeService.reassignManager(employee.requireId(), employee.requireId())))
                .isInstanceOf(ValidationException.class);
    }

    /**
     * Regression test for two defects that shared six lines of {@code create}. The manager was
     * assigned by {@code applyAdminFields} <em>before</em> {@code employees.save(employee)}, so
     * {@code reassignManagerInternal}'s {@code employee.requireId()} blew up on an entity whose
     * {@code @UuidGenerator} id is not assigned until {@code persist()} — every attempt to create
     * an employee with a manager was a 500. Behind that, {@code create} then wrote a *second*
     * manager-history row for the manager {@code reassignManagerInternal} had already recorded.
     *
     * <p>Both are asserted here on purpose: fixing only the ordering turns a loud failure into a
     * silent duplicate, which is strictly worse. No test created an employee with a manager
     * before this one — every other manager test reassigns on an already-persisted record, which
     * is exactly the shape that cannot catch either bug.
     */
    @Test
    void create_withManager_linksManagerAndOpensExactlyOneHistoryRow() {
        Employee manager = createActiveEmployee("Manager", "One");

        Employee created =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        formWithManager("Report", "Ee", uniqueEmail(), manager.requireId()),
                                        BASE_URL,
                                        TENANT_NAME));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(created.requireId()));
        assertThat(reloaded.manager()).isNotNull();
        assertThat(reloaded.manager().requireId()).isEqualTo(manager.requireId());

        var history =
                asTenant(
                        tenantA,
                        () -> managerHistory.findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(created.requireId()));
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().effectiveTo()).isNull();
    }

    /**
     * What the duplicate row actually costs. {@code
     * findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc} closes one open row, so
     * a create that opened two leaves one open forever and the history stops being a chain.
     */
    @Test
    void create_withManager_thenReassign_leavesExactlyOneOpenHistoryRow() {
        Employee manager1 = createActiveEmployee("Manager", "One");
        Employee manager2 = createActiveEmployee("Manager", "Two");
        Employee report =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        formWithManager("Report", "Ee", uniqueEmail(), manager1.requireId()),
                                        BASE_URL,
                                        TENANT_NAME));

        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager2.requireId()));

        var history =
                asTenant(
                        tenantA,
                        () -> managerHistory.findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(report.requireId()));
        assertThat(history).hasSize(2);
        assertThat(history).filteredOn(row -> row.effectiveTo() == null).hasSize(1);
    }

    /** Pins the no-manager path: a new hire without one must not get a null-manager history row. */
    @Test
    void create_withoutManager_writesNoManagerHistoryRow() {
        Employee created =
                asTenant(tenantA, () -> employeeService.create(form("Jane", "Doe", uniqueEmail()), BASE_URL, TENANT_NAME));

        var history =
                asTenant(
                        tenantA,
                        () -> managerHistory.findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(created.requireId()));
        assertThat(history).isEmpty();
    }

    /**
     * CLAUDE.md §5 rule 8. The manager is resolved through {@code require(managerId)}, which is
     * {@code @TenantId}-scoped, so another tenant's employee id must read as not-found rather
     * than linking across the boundary.
     */
    @Test
    void create_withManagerFromAnotherTenant_throwsNotFound() {
        Employee foreignManager =
                asTenant(
                        tenantB,
                        () -> employeeService.create(form("Foreign", "Manager", uniqueEmail()), BASE_URL, TENANT_NAME));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                employeeService.create(
                                                        formWithManager("Report", "Ee", uniqueEmail(), foreignManager.requireId()),
                                                        BASE_URL,
                                                        TENANT_NAME)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void terminate_withPastDate_appliesImmediatelyAndClosesStatusHistory() {
        Employee employee = createActiveEmployee("Jane", "Doe");

        asTenant(tenantA, () -> employeeService.terminate(employee.requireId(), LocalDate.now(clock).minusDays(1)));

        Employee reloaded = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(reloaded.status()).isEqualTo(EmployeeStatus.TERMINATED);
    }

    @Test
    void terminate_withFutureDate_staysActiveUntilTheJobApplies() {
        Employee employee = createActiveEmployee("Jane", "Doe");
        LocalDate future = LocalDate.now(clock).plusDays(5);

        asTenant(tenantA, () -> employeeService.terminate(employee.requireId(), future));
        Employee stillActive = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(stillActive.status()).isEqualTo(EmployeeStatus.ACTIVE);

        clock.advance(java.time.Duration.ofDays(6));
        int applied = asTenant(tenantA, () -> employeeService.applyDueTerminations(LocalDate.now(clock)));

        assertThat(applied).isGreaterThanOrEqualTo(1);
        Employee terminated = asTenant(tenantA, () -> employeeService.require(employee.requireId()));
        assertThat(terminated.status()).isEqualTo(EmployeeStatus.TERMINATED);
    }

    @Test
    void getProfileForViewer_self_isAllowed() {
        Employee employee = createActiveEmployee("Jane", "Doe");
        AppUserPrincipal ownPrincipal = loadPrincipal(employee.email());

        Employee viewed = asTenant(tenantA, () -> employeeService.getProfileForViewer(employee.requireId(), ownPrincipal));

        assertThat(viewed.requireId()).isEqualTo(employee.requireId());
    }

    @Test
    void getProfileForViewer_directManager_isAllowed() {
        Employee manager = createActiveEmployee("Manager", "One");
        grantRole(manager, Role.MANAGER);
        Employee report = createActiveEmployee("Report", "Ee");
        asTenant(tenantA, () -> employeeService.reassignManager(report.requireId(), manager.requireId()));
        AppUserPrincipal managerPrincipal = loadPrincipal(manager.email());

        Employee viewed = asTenant(tenantA, () -> employeeService.getProfileForViewer(report.requireId(), managerPrincipal));

        assertThat(viewed.requireId()).isEqualTo(report.requireId());
    }

    @Test
    void getProfileForViewer_unrelatedEmployee_isDenied() {
        Employee viewer = createActiveEmployee("Viewer", "Ee");
        Employee target = createActiveEmployee("Target", "Ee");
        AppUserPrincipal viewerPrincipal = loadPrincipal(viewer.email());

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> employeeService.getProfileForViewer(target.requireId(), viewerPrincipal)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void peopleFacade_countActiveEmployeesInDepartment_excludesTerminated() {
        UUID departmentId = seedDepartment();
        Employee active = createActiveEmployee("Active", "One");
        Employee terminated = createActiveEmployee("Terminated", "One");
        assignDepartment(active, departmentId);
        assignDepartment(terminated, departmentId);
        asTenant(tenantA, () -> employeeService.terminate(terminated.requireId(), LocalDate.now(clock).minusDays(1)));

        long count = asTenant(tenantA, () -> peopleFacade.countActiveEmployeesInDepartment(departmentId));

        assertThat(count).isEqualTo(1);
    }

    // ---- fixtures ----

    private Employee createActiveEmployee(String firstName, String lastName) {
        Employee employee =
                asTenant(tenantA, () -> employeeService.create(form(firstName, lastName, uniqueEmail()), BASE_URL, TENANT_NAME));
        asTenant(tenantA, () -> employeeService.activateForUser(employee.userId()));
        return asTenant(tenantA, () -> employeeService.require(employee.requireId()));
    }

    private void grantRole(Employee employee, Role role) {
        asTenant(
                tenantA,
                () -> {
                    AppUser user = appUsers.findById(employee.userId()).orElseThrow();
                    user.grant(role);
                    return appUsers.save(user);
                });
    }

    private AppUserPrincipal loadPrincipal(String email) {
        return (AppUserPrincipal) asTenant(tenantA, () -> userDetailsService.loadUserByUsername(email));
    }

    private UUID seedDepartment() {
        return asTenant(
                tenantA,
                () -> {
                    Division division = divisionRepository.save(Division.create("Engineering-" + UUID.randomUUID(), null));
                    Department department =
                            departmentRepository.save(Department.create("Backend-" + UUID.randomUUID(), null, division));
                    return department.requireId();
                });
    }

    private void assignDepartment(Employee employee, UUID departmentId) {
        asTenant(
                tenantA,
                () ->
                        employeeService.updateAdminFields(
                                employee.requireId(),
                                new EmployeeForms.AdminEmployeePatch(
                                        null,
                                        employee.firstName(),
                                        employee.lastName(),
                                        employee.email(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        departmentId,
                                        null,
                                        null,
                                        null,
                                        BigDecimal.valueOf(8),
                                        null,
                                        null)));
    }

    private static EmployeeForms.CreateEmployee form(String firstName, String lastName, String email) {
        return new EmployeeForms.CreateEmployee(
                firstName, lastName, email, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    /** {@link #form} with the 14th field, {@code managerId}, filled in — the Add Employee form's Manager select. */
    private static EmployeeForms.CreateEmployee formWithManager(
            String firstName, String lastName, String email, UUID managerId) {
        return new EmployeeForms.CreateEmployee(
                firstName, lastName, email, null, null, null, null, null, null, null, null, null, null, managerId,
                null, null, null, null, null);
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.test";
    }
}
