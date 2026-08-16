package com.helyx.helyxhr.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helyx.helyxhr.TestcontainersConfiguration;
import com.helyx.helyxhr.identity.AppUser;
import com.helyx.helyxhr.identity.AppUserDetailsService;
import com.helyx.helyxhr.identity.AppUserRepository;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.people.Employee;
import com.helyx.helyxhr.people.EmployeeForms;
import com.helyx.helyxhr.people.EmployeeService;
import com.helyx.helyxhr.tenant.Tenant;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantRepository;
import com.helyx.helyxhr.timeoff.LeaveTypeService;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PRD §24.2's Welcome widget: "Welcome, {FirstName}!". Uses a real {@code AppUserPrincipal} (via
 * {@link AppUserDetailsService}), not the generic {@code user(String)} post-processor —
 * {@code HomeController} binds {@code @AuthenticationPrincipal AppUserPrincipal}, which only
 * resolves against the real type (see {@link ProfileAccessControlTest}'s javadoc for the same
 * gotcha).
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class HomeControllerTest {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private EmployeeService employeeService;
    @Autowired private AppUserRepository appUsers;
    @Autowired private AppUserDetailsService userDetailsService;
    @Autowired private LeaveTypeService leaveTypeService;

    private String slug;
    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        slug = "home" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant", () -> tenants.save(new Tenant(slug, "Home Co")).getId());
    }

    @Test
    void home_asEmployeeWithLinkedProfile_greetsByFirstName() throws Exception {
        Employee employee = createEmployee("Jane", "Doe");
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome, Jane!")));
    }

    /**
     * Mirrors an Admin-only account with no {@link Employee} row — {@code DevDataSeeder}'s dev
     * bootstrap admin is exactly this shape (see CLAUDE.md §5's PRD §5 note that this should never
     * happen in production). The greeting must degrade, not fail the page.
     */
    @Test
    void home_withNoLinkedEmployee_showsGenericGreeting() throws Exception {
        UserDetails principal = createAdminWithNoEmployee("admin-no-profile@home.test");

        mockMvc
                .perform(url("/").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome!")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Welcome, "))));
    }

    @Test
    void home_withGrantedBalance_showsBookTimeOffCard() throws Exception {
        run(
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));
        Employee employee = createEmployee("Priya", "Shah", LocalDate.now());
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Book Time Off")))
                .andExpect(content().string(containsString("Annual")));
    }

    private Employee createEmployee(String firstName, String lastName) {
        return createEmployee(firstName, lastName, null);
    }

    private Employee createEmployee(String firstName, String lastName, LocalDate hireDate) {
        Employee employee =
                run(
                        () ->
                                employeeService.create(
                                        new EmployeeForms.CreateEmployee(
                                                firstName,
                                                lastName,
                                                UUID.randomUUID() + "@example.test",
                                                null, null, null, null, null, null, null, hireDate, null, null,
                                                null, null, null, null, null, null),
                                        BASE_URL,
                                        TENANT_NAME));
        run(() -> employeeService.activateForUser(employee.userId()));
        return run(() -> employeeService.require(employee.requireId()));
    }

    private UserDetails createAdminWithNoEmployee(String email) {
        run(
                () -> {
                    AppUser admin = AppUser.active(email, "{noop}unused");
                    admin.grant(Role.ADMIN);
                    return appUsers.save(admin);
                });
        return loadPrincipal(email);
    }

    private UserDetails loadPrincipal(String email) {
        return run(() -> userDetailsService.loadUserByUsername(email));
    }

    private <T> T run(Supplier<T> action) {
        TenantContext.set(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void run(Runnable action) {
        TenantContext.set(tenantId);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder url(String path) {
        return get(URI.create("http://" + slug + ".localhost" + path));
    }
}
