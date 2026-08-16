package com.helyx.helyxhr.timeoff;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.NotFoundException;
import com.helyx.helyxhr.common.ValidationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Public holiday calendar CRUD, one row at a time or via CSV bulk upload (PRD §12.1, §12.5). The
 * CSV is parsed in-request and discarded, never persisted as a document — {@code FileStorage}
 * doesn't exist until Phase 1.7, and there is no reason to retain the raw upload once its rows
 * are in {@code public_holiday}.
 */
@Service
public class PublicHolidayService {

    private static final Logger log = LoggerFactory.getLogger(PublicHolidayService.class);

    /** A holiday calendar is a few dozen rows at most; this is generous headroom, not a target. */
    private static final long MAX_UPLOAD_BYTES = 1_000_000;

    /**
     * CSV has no unique magic number — Tika (or any sniffer) reports plain delimited text as
     * {@code text/plain}, so both are accepted alongside the {@code .csv} extension check
     * (CLAUDE.md §6 A03: extension whitelist + magic-byte check together, neither alone).
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("text/csv", "text/plain");

    private static final String EXPECTED_HEADER = "date,name";

    private final PublicHolidayRepository holidays;
    private final Tika tika = new Tika();

    PublicHolidayService(PublicHolidayRepository holidays) {
        this.holidays = holidays;
    }

    @Transactional(readOnly = true)
    public List<PublicHoliday> listAll() {
        return holidays.findAllByOrderByDateAsc();
    }

    @Transactional
    public PublicHoliday create(LocalDate date, String name) {
        if (holidays.existsByDateAndNameIgnoreCase(date, name)) {
            throw new ConflictException(
                    "PUBLIC_HOLIDAY_DUPLICATE", "\"" + name + "\" on " + date + " already exists");
        }
        PublicHoliday saved = holidays.save(PublicHoliday.create(date, name));
        log.info("Created public holiday {}", saved.requireId());
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        PublicHoliday holiday =
                holidays
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "PUBLIC_HOLIDAY_NOT_FOUND", "Public holiday not found"));
        holidays.delete(holiday);
        log.info("Deleted public holiday {}", id);
    }

    /**
     * Combined write-then-read for {@code AdminLeaveController}'s mutation endpoints — see {@code
     * LeaveTypeService.createAndList}'s Javadoc for why (ADR 0007).
     */
    @Transactional
    public List<PublicHoliday> createAndList(LocalDate date, String name) {
        create(date, name);
        return listAll();
    }

    @Transactional
    public List<PublicHoliday> deleteAndList(UUID id) {
        delete(id);
        return listAll();
    }

    /**
     * Whole-file validation, not best-effort per-row insertion: any invalid row rejects the whole
     * upload with a full list of what's wrong, so the Admin fixes the source file once rather
     * than untangling a partially-applied calendar.
     */
    @Transactional
    public CsvUploadResult bulkUpload(MultipartFile file) {
        validateFileEnvelope(file);
        List<String> lines = readLines(file);
        CsvParseOutcome outcome = parseAndValidate(lines);
        if (!outcome.errors().isEmpty()) {
            return new CsvUploadResult(0, outcome.errors(), listAll());
        }
        for (HolidayRow row : outcome.rows()) {
            holidays.save(PublicHoliday.create(row.date(), row.name()));
        }
        log.info("Bulk-uploaded {} public holiday(s) from CSV", outcome.rows().size());
        return new CsvUploadResult(outcome.rows().size(), List.of(), listAll());
    }

    private void validateFileEnvelope(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ValidationException("HOLIDAY_CSV_EMPTY", "The uploaded file is empty");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ValidationException(
                    "HOLIDAY_CSV_TOO_LARGE", "The uploaded file exceeds the " + MAX_UPLOAD_BYTES + " byte limit");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ValidationException("HOLIDAY_CSV_BAD_EXTENSION", "Only .csv files are accepted");
        }
        String detected;
        try (InputStream in = file.getInputStream()) {
            detected = tika.detect(in, filename);
        } catch (IOException e) {
            throw new ValidationException("HOLIDAY_CSV_UNREADABLE", "Could not read the uploaded file");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(detected)) {
            throw new ValidationException(
                    "HOLIDAY_CSV_BAD_CONTENT_TYPE",
                    "The uploaded file's content doesn't look like CSV (detected: " + detected + ")");
        }
    }

    private List<String> readLines(MultipartFile file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            throw new ValidationException("HOLIDAY_CSV_UNREADABLE", "Could not read the uploaded file");
        }
        return lines;
    }

    /**
     * Splits each line on the first comma only (date, then everything else as the name) rather
     * than pulling in a full CSV parsing library for a two-column, no-embedded-commas-in-dates
     * format — a deliberate simplicity trade-off (CLAUDE.md §11: no dependency without
     * justification). A holiday name containing a comma still parses correctly; a *date* field
     * cannot contain one, which ISO dates never do.
     */
    private CsvParseOutcome parseAndValidate(List<String> lines) {
        List<HolidayRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();
        int lineNumber = 0;
        boolean sawHeader = false;
        for (String rawLine : lines) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!sawHeader && line.equalsIgnoreCase(EXPECTED_HEADER)) {
                sawHeader = true;
                continue;
            }
            sawHeader = true;

            String[] columns = line.split(",", 2);
            if (columns.length != 2) {
                errors.add("Line " + lineNumber + ": expected \"date,name\", got \"" + line + "\"");
                continue;
            }
            String dateText = stripQuotes(columns[0].strip());
            String name = stripQuotes(columns[1].strip());
            if (name.isEmpty()) {
                errors.add("Line " + lineNumber + ": holiday name is blank");
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(dateText);
            } catch (DateTimeParseException e) {
                errors.add("Line " + lineNumber + ": \"" + dateText + "\" is not a valid date (expected YYYY-MM-DD)");
                continue;
            }
            String dedupeKey = date + "|" + name.toLowerCase(Locale.ROOT);
            if (!seenInFile.add(dedupeKey)) {
                errors.add("Line " + lineNumber + ": duplicate row for \"" + name + "\" on " + date);
                continue;
            }
            if (holidays.existsByDateAndNameIgnoreCase(date, name)) {
                errors.add("Line " + lineNumber + ": \"" + name + "\" on " + date + " already exists");
                continue;
            }
            rows.add(new HolidayRow(date, name));
        }
        if (rows.isEmpty() && errors.isEmpty()) {
            errors.add("The file contains no holiday rows");
        }
        return new CsvParseOutcome(rows, errors);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record HolidayRow(LocalDate date, String name) {}

    private record CsvParseOutcome(List<HolidayRow> rows, List<String> errors) {}

    public record CsvUploadResult(int createdCount, List<String> errors, List<PublicHoliday> holidays) {}
}
