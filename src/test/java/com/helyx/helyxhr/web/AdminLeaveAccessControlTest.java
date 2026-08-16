package com.helyx.helyxhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helyx.helyxhr.TestcontainersConfiguration;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CLAUDE.md §8: one 200 test and one 403 test per protected endpoint per role. Permissions come
 * from PRD §26 — CRUD leave types, uploading public holidays, and (by extension, no separate PRD
 * row) manually adjusting a balance are all Admin-only. Status-only, matching {@code
 * AdminOrganizationAccessControlTest} (see its own doc comment for why).
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AdminLeaveAccessControlTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TransactionTemplate transactions;

    private String slug;

    @BeforeEach
    void seedTenant() {
        slug = "leave-rbac" + UUID.randomUUID().toString().substring(0, 8);
        TenantContext.runAsSystem(
                "test: seed tenant",
                () -> transactions.execute(status -> tenants.save(new Tenant(slug, "Leave RBAC Co")).getId()));
    }

    @Test
    void leaveTypesPage_asAdmin_returns200() throws Exception {
        mockMvc.perform(url("/admin/leave-types").with(user("admin@leave.test").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void leaveTypesPage_asEmployee_returns403() throws Exception {
        mockMvc.perform(url("/admin/leave-types").with(user("employee@leave.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void leaveTypesPage_asManager_returns403() throws Exception {
        mockMvc.perform(url("/admin/leave-types").with(user("manager@leave.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void leaveTypesPage_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(url("/admin/leave-types")).andExpect(status().is3xxRedirection());
    }

    @Test
    void holidaysPage_asAdmin_returns200() throws Exception {
        mockMvc.perform(url("/admin/holidays").with(user("admin@leave.test").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void holidaysPage_asEmployee_returns403() throws Exception {
        mockMvc.perform(url("/admin/holidays").with(user("employee@leave.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void holidaysPage_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(url("/admin/holidays")).andExpect(status().is3xxRedirection());
    }

    @Test
    void createLeaveType_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/admin/leave-types"))
                                .param("name", "Annual")
                                .param("defaultAnnualAllowance", "30")
                                .with(user("employee@leave.test").roles("EMPLOYEE"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createHoliday_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/admin/holidays"))
                                .param("date", "2026-01-01")
                                .param("name", "New Year")
                                .with(user("employee@leave.test").roles("EMPLOYEE"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void activateLeaveType_asManager_returns403() throws Exception {
        mockMvc
                .perform(
                        post(URI.create(
                                        "http://"
                                                + slug
                                                + ".localhost/admin/leave-types/"
                                                + UUID.randomUUID()
                                                + "/activate"))
                                .with(user("manager@leave.test").roles("MANAGER"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteHoliday_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        delete(URI.create(
                                        "http://" + slug + ".localhost/admin/holidays/" + UUID.randomUUID()))
                                .with(user("employee@leave.test").roles("EMPLOYEE"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adjustBalance_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        post(URI.create(
                                        "http://"
                                                + slug
                                                + ".localhost/admin/employees/"
                                                + UUID.randomUUID()
                                                + "/leave-balances/"
                                                + UUID.randomUUID()
                                                + "/adjust"))
                                .param("newGranted", "10")
                                .param("reason", "Correction")
                                .with(user("employee@leave.test").roles("EMPLOYEE"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder url(String path) {
        return get(URI.create("http://" + slug + ".localhost" + path));
    }
}
