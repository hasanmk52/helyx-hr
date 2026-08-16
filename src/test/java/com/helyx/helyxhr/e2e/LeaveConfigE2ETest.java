package com.helyx.helyxhr.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.helyx.helyxhr.TestcontainersConfiguration;
import com.helyx.helyxhr.identity.AppUser;
import com.helyx.helyxhr.identity.AppUserRepository;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.notifications.system.EmailOutbox;
import com.helyx.helyxhr.notifications.system.EmailOutboxRepository;
import com.helyx.helyxhr.tenant.Tenant;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantRepository;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 1.5's DoD headline flow, end to end through a real browser (CLAUDE.md §3, §8): Admin
 * defines a leave type, adds a holiday, creates a hired employee — the employee's balance appears
 * on their Home dashboard. CSV bulk upload itself is covered by {@code
 * PublicHolidayServiceTest}'s integration tests, not repeated here; this spec's job is proving
 * the browser-rendered path, one manually-added holiday is enough for that.
 *
 * <p>Mirrors {@code EmployeeLifecycleE2ETest}'s helpers (login, invite-token-from-outbox) rather
 * than re-deriving them, per CURRENT_PHASE.md.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaveConfigE2ETest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_%\\-]+)");
    private static final String EMPLOYEE_PASSWORD = "NewPassphrase1";

    @LocalServerPort private int port;

    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository appUsers;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EmailOutboxRepository outbox;

    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;
    private Page page;

    private String baseUrl;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void newPage() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void adminDefinesLeaveTypeAndHoliday_employeeSeesBalanceOnHome() {
        String slug = "leave-e2e" + UUID.randomUUID().toString().substring(0, 8);
        baseUrl = "http://" + slug + ".localhost:" + port;
        String adminEmail = "admin-" + UUID.randomUUID() + "@example.test";
        String employeeEmail = "employee-" + UUID.randomUUID() + "@example.test";
        seedTenantAndAdmin(slug, adminEmail);

        loginAs(adminEmail, "AdminPassphrase1");
        assertThat(page.url()).isEqualTo(baseUrl + "/");

        createLeaveType();
        createHoliday();
        createEmployeeWithHireDate(employeeEmail);

        String rawToken = tokenFromLastEmailTo(employeeEmail);
        acceptInvite(rawToken);
        loginAs(employeeEmail, EMPLOYEE_PASSWORD);

        assertThat(page.url()).isEqualTo(baseUrl + "/");
        page.waitForSelector(".card:has-text('Annual')");
        assertThat(page.content()).contains("Book Time Off");
    }

    private void seedTenantAndAdmin(String slug, String adminEmail) {
        // Two separate calls, not nested — see EmployeeLifecycleE2ETest's identical comment.
        UUID tenantId =
                TenantContext.runAsSystem(
                        "e2e test: seed tenant", () -> tenants.save(new Tenant(slug, "E2E Co")).getId());

        TenantContext.set(tenantId);
        try {
            AppUser admin = AppUser.active(adminEmail, passwordEncoder.encode("AdminPassphrase1"));
            admin.grant(Role.ADMIN);
            appUsers.save(admin);
        } finally {
            TenantContext.clear();
        }
    }

    private void loginAs(String email, String password) {
        page.navigate(baseUrl + "/login");
        page.fill("#email", email);
        page.fill("#password", password);
        page.click("button[type=submit]");
    }

    private void createLeaveType() {
        page.navigate(baseUrl + "/admin/leave-types");
        page.click("button:has-text('Add Leave Type')");
        page.waitForSelector("#leave-type-name");
        page.fill("#leave-type-name", "Annual");
        page.fill("#leave-type-defaultAnnualAllowance", "24");
        page.click("#leaveTypeOffcanvasBody button:has-text('Save leave type')");
        page.waitForSelector(".toast-body:has-text('Leave type created')");
    }

    private void createHoliday() {
        page.navigate(baseUrl + "/admin/holidays");
        page.click("button:has-text('Add Holiday')");
        page.waitForSelector("#holiday-date");
        page.fill("#holiday-date", LocalDate.now().plusMonths(1).toString());
        page.fill("#holiday-name", "Founders Day");
        page.click("#holidayOffcanvasBody button:has-text('Save holiday')");
        page.waitForSelector(".toast-body:has-text('Holiday added')");
    }

    private void createEmployeeWithHireDate(String email) {
        page.navigate(baseUrl + "/admin/employees");
        page.click("button:has-text('Add Employee')");
        page.waitForSelector("#create-firstName");
        page.fill("#create-firstName", "Priya");
        page.fill("#create-lastName", "Shah");
        page.fill("#create-email", email);
        page.fill("#create-hireDate", LocalDate.now().toString());
        page.click("#employeeOffcanvasBody button:has-text('Create')");
        page.waitForSelector(".toast-body:has-text('Employee created')");
    }

    private void acceptInvite(String rawToken) {
        page.navigate(baseUrl + "/accept-invite?token=" + rawToken);
        page.fill("#password", EMPLOYEE_PASSWORD);
        page.fill("#confirmPassword", EMPLOYEE_PASSWORD);
        page.click("button[type=submit]");
    }

    /** {@code email_outbox} is system-scoped (no RLS), same as {@code EmployeeLifecycleE2ETest}. */
    private String tokenFromLastEmailTo(String email) {
        List<EmailOutbox> found =
                TenantContext.runAsSystem(
                        "e2e test: read outbox",
                        () -> outbox.findAll().stream().filter(row -> row.toEmail().equals(email)).toList());
        assertThat(found).as("queued invite email to %s", email).isNotEmpty();
        Matcher matcher = TOKEN_IN_LINK.matcher(found.getLast().bodyHtml());
        assertThat(matcher.find()).as("token in email body").isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }
}
