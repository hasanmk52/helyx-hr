package com.helyx.helyxhr.timeoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.ValidationException;
import com.helyx.helyxhr.tenantisolation.TenantIsolationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class PublicHolidayServiceTest extends TenantIsolationTestBase {

    @Autowired private PublicHolidayService holidayService;

    @Test
    void create_persistsAHoliday() {
        PublicHoliday saved =
                asTenant(tenantA, () -> holidayService.create(LocalDate.of(2026, 1, 1), "New Year's Day"));

        assertThat(saved.name()).isEqualTo("New Year's Day");
        assertThat(saved.date()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void create_duplicateDateAndName_throwsConflict() {
        asTenant(tenantA, () -> holidayService.create(LocalDate.of(2026, 1, 1), "New Year's Day"));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () -> holidayService.create(LocalDate.of(2026, 1, 1), "New Year's Day")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void bulkUpload_validCsv_createsAllRows() {
        String csv = "date,name\n2026-01-01,New Year's Day\n2026-12-02,UAE National Day\n";
        MultipartFile file = csvFile(csv);

        PublicHolidayService.CsvUploadResult result = asTenant(tenantA, () -> holidayService.bulkUpload(file));

        assertThat(result.errors()).isEmpty();
        assertThat(result.createdCount()).isEqualTo(2);
        List<PublicHoliday> all = asTenant(tenantA, holidayService::listAll);
        assertThat(all).hasSize(2);
    }

    @Test
    void bulkUpload_holidayNameContainingAComma_parsesCorrectly() {
        String csv = "date,name\n2026-12-25,Christmas, Boxing Day\n";
        MultipartFile file = csvFile(csv);

        PublicHolidayService.CsvUploadResult result = asTenant(tenantA, () -> holidayService.bulkUpload(file));

        assertThat(result.errors()).isEmpty();
        List<PublicHoliday> all = asTenant(tenantA, holidayService::listAll);
        assertThat(all.getFirst().name()).isEqualTo("Christmas, Boxing Day");
    }

    @Test
    void bulkUpload_malformedRow_rejectsWholeFileAndCreatesNothing() {
        String csv = "date,name\n2026-01-01,New Year's Day\nnot-a-date,Bad Row\n";
        MultipartFile file = csvFile(csv);

        PublicHolidayService.CsvUploadResult result = asTenant(tenantA, () -> holidayService.bulkUpload(file));

        assertThat(result.errors()).isNotEmpty();
        assertThat(result.createdCount()).isZero();
        assertThat(asTenant(tenantA, holidayService::listAll)).isEmpty();
    }

    @Test
    void bulkUpload_wrongExtension_throwsValidationException() {
        MultipartFile file =
                new MockMultipartFile(
                        "file", "holidays.txt", "text/plain", "date,name\n2026-01-01,New Year's Day\n"
                                .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> asTenant(tenantA, () -> holidayService.bulkUpload(file)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void bulkUpload_emptyFile_throwsValidationException() {
        MultipartFile file = new MockMultipartFile("file", "holidays.csv", "text/csv", new byte[0]);

        assertThatThrownBy(() -> asTenant(tenantA, () -> holidayService.bulkUpload(file)))
                .isInstanceOf(ValidationException.class);
    }

    private MultipartFile csvFile(String content) {
        return new MockMultipartFile(
                "file", "holidays.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
