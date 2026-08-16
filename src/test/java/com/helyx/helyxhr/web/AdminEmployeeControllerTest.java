package com.helyx.helyxhr.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helyx.helyxhr.TestcontainersConfiguration;
import com.helyx.helyxhr.org.Department;
import com.helyx.helyxhr.org.DepartmentRepository;
import com.helyx.helyxhr.org.Division;
import com.helyx.helyxhr.org.DivisionRepository;
import com.helyx.helyxhr.people.EmployeeRepository;
import com.helyx.helyxhr.tenant.Tenant;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantRepository;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression coverage for the {@code LazyInitializationException} an Employee with a
 * {@code department} used to throw once it left {@link com.helyx.helyxhr.people.EmployeeService}'s
 * transaction: {@code spring.jpa.open-in-view: false} means no Hibernate session survives past
 * that boundary, so the lazy {@code department}/{@code manager} associations {@code
 * AdminEmployeeController#rows} and {@code people/profile.html} read afterward need to already be
 * loaded ({@link com.helyx.helyxhr.people.EmployeeRepository}'s {@code @EntityGraph}). Every
 * fixture here deliberately assigns a department — the bug never showed up in
 * {@code AdminEmployeeAccessControlTest} because its fixtures never did.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AdminEmployeeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private DivisionRepository divisions;
    @Autowired private DepartmentRepository departments;
    @Autowired private EmployeeRepository employees;
    @Autowired private TransactionTemplate transactions;

    private String slug;
    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        slug = "emp-ctrl" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant",
                        () -> transactions.execute(status -> tenants.save(new Tenant(slug, "Emp Ctrl Co")).getId()));
    }

    /**
     * Covers both {@code rows()} call sites the {@code @EntityGraph} fixes at once: {@code
     * create()}'s own {@code populateContentAttributes} (the create response itself) and the
     * {@code GET} list handler (a fresh request, fresh persistence context, the same shape of bug
     * report the user filed). Kept as one test rather than two — both paths call the identical
     * {@code employees.list(null, null)} query, so a second test seeded by repeating this same
     * POST would only ever fail at the shared setup step, never at its own assertion.
     */
    @Test
    void createEmployeeWithDepartment_thenReloadingTheList_bothRenderDepartmentNameWithoutError()
            throws Exception {
        UUID departmentId = seedDepartment("Engineering");

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/admin/employees"))
                                .param("firstName", "Jane")
                                .param("lastName", "Doe")
                                .param("email", "jane@emp-ctrl.test")
                                .param("departmentId", departmentId.toString())
                                .with(admin())
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Engineering")));

        mockMvc
                .perform(get(URI.create("http://" + slug + ".localhost/admin/employees")).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Engineering")));
    }

    /**
     * The user-facing half of the {@code create}-with-a-manager fix: the service-level proof lives
     * in {@code EmployeeServiceTest}, but only a real form POST shows that the Manager select on
     * the Add Employee offcanvas ({@code people/list.html}) no longer produces a 500. Before the
     * fix this returned an error page, because {@code create} performed the manager transition
     * before {@code save()} had assigned the employee an id.
     */
    @Test
    void createEmployeeWithManager_returnsOkRatherThanServerError() throws Exception {
        UUID departmentId = seedDepartment("Operations");
        UUID managerId = createEmployeeReturningId("Boss", "Person", "boss@emp-ctrl.test", departmentId);

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/admin/employees"))
                                .param("firstName", "Report")
                                .param("lastName", "Person")
                                .param("email", "report@emp-ctrl.test")
                                .param("departmentId", departmentId.toString())
                                .param("managerId", managerId.toString())
                                .with(admin())
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Report")));
    }

    /** Creates through the real endpoint, then reads the id back the way a follow-up request would. */
    private UUID createEmployeeReturningId(
            String firstName, String lastName, String email, UUID departmentId) throws Exception {
        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/admin/employees"))
                                .param("firstName", firstName)
                                .param("lastName", lastName)
                                .param("email", email)
                                .param("departmentId", departmentId.toString())
                                .with(admin())
                                .with(csrf()))
                .andExpect(status().isOk());

        TenantContext.set(tenantId);
        try {
            return transactions.execute(
                    status ->
                            employees.findAllByOrderByLastNameAscFirstNameAsc().stream()
                                    .filter(employee -> employee.email().equals(email))
                                    .findFirst()
                                    .orElseThrow()
                                    .requireId());
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedDepartment(String name) {
        TenantContext.set(tenantId);
        try {
            return transactions.execute(
                    status -> {
                        Division division = divisions.save(Division.create("Product", null));
                        return departments.save(Department.create(name, null, division)).getId();
                    });
        } finally {
            TenantContext.clear();
        }
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin() {
        return user("admin@emp-ctrl.test").roles("ADMIN");
    }
}
