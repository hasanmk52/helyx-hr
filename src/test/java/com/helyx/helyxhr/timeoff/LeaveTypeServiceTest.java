package com.helyx.helyxhr.timeoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.NotFoundException;
import com.helyx.helyxhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LeaveTypeServiceTest extends TenantIsolationTestBase {

    @Autowired private LeaveTypeService leaveTypeService;

    @Test
    void create_persistsWithSuppliedFieldsAndDefaultsActive() {
        LeaveType saved =
                asTenant(
                        tenantA,
                        () ->
                                leaveTypeService.create(
                                        "Annual",
                                        "bi-sun",
                                        "#2563EB",
                                        true,
                                        true,
                                        false,
                                        true,
                                        new BigDecimal("30"),
                                        "Standard annual leave"));

        assertThat(saved.name()).isEqualTo("Annual");
        assertThat(saved.active()).isTrue();
        assertThat(saved.defaultAnnualAllowance()).isEqualByComparingTo("30");
    }

    @Test
    void create_duplicateNameInSameTenant_throwsConflict() {
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                leaveTypeService.create(
                                                        "Annual",
                                                        null,
                                                        null,
                                                        true,
                                                        true,
                                                        false,
                                                        true,
                                                        new BigDecimal("10"),
                                                        null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_sameNameInDifferentTenants_bothSucceed() {
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));
        LeaveType tenantBType =
                asTenant(
                        tenantB,
                        () ->
                                leaveTypeService.create(
                                        "Annual", null, null, true, true, false, true, new BigDecimal("20"), null));

        assertThat(tenantBType.defaultAnnualAllowance()).isEqualByComparingTo("20");
    }

    @Test
    void deactivate_thenListAll_stillReturnsIt() {
        LeaveType saved =
                asTenant(
                        tenantA,
                        () ->
                                leaveTypeService.create(
                                        "Sick", null, null, true, false, false, true, new BigDecimal("10"), null));

        asTenant(
                tenantA,
                () -> {
                    leaveTypeService.deactivate(saved.requireId());
                    return null;
                });

        List<LeaveType> all = asTenant(tenantA, leaveTypeService::listAll);
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().active()).isFalse();
    }

    @Test
    void require_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> asTenant(tenantA, () -> leaveTypeService.require(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }
}
