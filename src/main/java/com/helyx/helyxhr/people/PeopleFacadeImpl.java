package com.helyx.helyxhr.people;

import com.helyx.helyxhr.common.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PeopleFacadeImpl implements PeopleFacade {

    private final EmployeeRepository employees;

    PeopleFacadeImpl(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveEmployeesInDepartment(UUID departmentId) {
        return employees.countByDepartmentIdAndStatusNot(departmentId, EmployeeStatus.TERMINATED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeHireInfo> listActiveEmployeeHireInfo() {
        return employees.findAllByStatusNot(EmployeeStatus.TERMINATED).stream()
                .map(e -> new EmployeeHireInfo(e.requireId(), e.hireDate()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeHireInfo requireEmployeeHireInfo(UUID employeeId) {
        Employee employee =
                employees
                        .findById(employeeId)
                        .orElseThrow(
                                () -> new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"));
        return new EmployeeHireInfo(employee.requireId(), employee.hireDate());
    }
}
