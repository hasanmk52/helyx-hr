package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.common.TenantAwareRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** No method here mentions tenant_id, and none may (CLAUDE.md §5 rule 4). */
@Repository
public interface LeaveTypeRepository extends TenantAwareRepository<LeaveType> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    List<LeaveType> findAllByOrderByNameAsc();

    /** Drives {@code BalanceService.grantAnnual} — every type an employee should have a balance for. */
    List<LeaveType> findAllByActiveTrue();
}
