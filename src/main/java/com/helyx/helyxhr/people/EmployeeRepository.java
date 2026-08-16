package com.helyx.helyxhr.people;

import com.helyx.helyxhr.common.TenantAwareRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** No method here mentions tenant_id, and none may (CLAUDE.md §5 rule 4). */
@Repository
public interface EmployeeRepository extends TenantAwareRepository<Employee> {

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, UUID id);

    /**
     * {@code department}/{@code manager} are {@code FetchType.LAZY} (Employee's doc comment), and
     * {@code spring.jpa.open-in-view: false} means no Hibernate session survives past the owning
     * {@code @Transactional} method — every caller that reads this outside that boundary (every
     * web-layer DTO mapping and every Thymeleaf template touching {@code employee.department.name}
     * or {@code employee.manager.fullName}) would otherwise hit {@code LazyInitializationException}.
     * Fetching both eagerly here, once, is simpler than remembering it at every call site.
     */
    @EntityGraph(attributePaths = {"department", "manager"})
    @Override
    Optional<Employee> findById(UUID id);

    @EntityGraph(attributePaths = {"department", "manager"})
    Optional<Employee> findByUserId(UUID userId);

    /** Drives the Department delete-guard's employee count (via {@link PeopleFacade}). */
    long countByDepartmentIdAndStatusNot(UUID departmentId, EmployeeStatus status);

    /** Drives {@link PeopleFacade#listActiveEmployeeHireInfo()} — no department/manager join needed. */
    List<Employee> findAllByStatusNot(EmployeeStatus status);

    @EntityGraph(attributePaths = {"department", "manager"})
    List<Employee> findAllByOrderByLastNameAscFirstNameAsc();

    @EntityGraph(attributePaths = {"department", "manager"})
    List<Employee> findAllByDepartmentIdAndStatusOrderByLastNameAscFirstNameAsc(
            UUID departmentId, EmployeeStatus status);

    @EntityGraph(attributePaths = {"department", "manager"})
    List<Employee> findAllByStatusOrderByLastNameAscFirstNameAsc(EmployeeStatus status);

    @EntityGraph(attributePaths = {"department", "manager"})
    List<Employee> findAllByDepartmentIdOrderByLastNameAscFirstNameAsc(UUID departmentId);

    /** Drives {@link EmployeeTerminationJob}: future-dated terminations that have now arrived. */
    List<Employee> findAllByTerminationDateLessThanEqualAndStatusNot(
            LocalDate onOrBefore, EmployeeStatus status);

    /**
     * Whether {@code managerId} is a direct or indirect manager of {@code employeeId}, walking the
     * self-referencing {@code manager_id} chain. Backs the "manager may view a report's profile,
     * direct or indirect" rule (PRD §26) — {@code EmployeeService} is the only caller.
     *
     * <p>A native query, not JPQL: recursive CTEs have no JPQL equivalent. Confined to this
     * Repository interface per CLAUDE.md §8's ArchUnit rule; RLS (not Hibernate's {@code
     * @TenantId}, which native queries bypass) still scopes every row this reads, since {@code
     * TenantSessionVariableListener} has already set {@code app.tenant_id} on this transaction's
     * connection (ADR 0004). Table names are schema-qualified because native SQL, unlike
     * Hibernate-generated SQL, is not rewritten by {@code hibernate.default_schema}.
     */
    @Query(
            value =
                    """
                    WITH RECURSIVE ancestors AS (
                      SELECT manager_id FROM helyx_hr.employee WHERE id = :employeeId
                      UNION ALL
                      SELECT e.manager_id FROM helyx_hr.employee e
                        JOIN ancestors a ON e.id = a.manager_id
                    )
                    SELECT EXISTS (SELECT 1 FROM ancestors WHERE manager_id = :managerId)
                    """,
            nativeQuery = true)
    boolean isManagerOf(@Param("managerId") UUID managerId, @Param("employeeId") UUID employeeId);
}
