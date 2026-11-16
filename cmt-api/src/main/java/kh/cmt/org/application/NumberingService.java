package kh.cmt.org.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import kh.cmt.org.api.NumberingPreview;
import kh.cmt.org.api.NumberingSettings;
import kh.cmt.org.api.StaffIdGenerator;
import kh.cmt.org.api.StaffRole;
import kh.cmt.org.domain.SchoolSettings;
import kh.cmt.org.infrastructure.SchoolSettingsRepository;
import kh.cmt.org.infrastructure.StaffIdSequenceStore;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings, Numbering: how Staff IDs look, a preview of the next ones, and the generator that hands them out.
 * <p>
 * A Staff ID is {@code PREFIX [ROLE] [YY] NUMBER} joined by the separator. The number comes from a counter per
 * school, prefix and year that only goes up ("never reused, even after a person is moved to inactive").
 */
@Service
@Transactional
public class NumberingService implements StaffIdGenerator {

    static final int MAX_SKIPPED = 10_000;

    private final SettingsService settings;
    private final SchoolSettingsRepository repository;
    private final StaffIdSequenceStore sequences;
    private final SchoolContext schoolContext;
    private final Clock clock;

    NumberingService(SettingsService settings, SchoolSettingsRepository repository, StaffIdSequenceStore sequences,
            SchoolContext schoolContext, Clock clock) {
        this.settings = settings;
        this.repository = repository;
        this.sequences = sequences;
        this.schoolContext = schoolContext;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ the Numbering section

    // not read-only: the first read for a new school creates its settings row
    public NumberingSettings numbering() {
        return view(settings.currentRow());
    }

    public NumberingSettings save(NumberingSettings in) {
        SchoolSettings row = settings.currentRow();
        SettingsService.checkVersion(in.version(), row.getNumberingRev());
        NumberingSettings clean = normalize(in);
        row.setNumberingPrefix(clean.prefix());
        row.setNumberingSeparator(clean.separator());
        row.setNumberingLength(clean.length());
        row.setNumberingRolePrefix(clean.rolePrefix());
        row.setNumberingYear(clean.includeYear());
        row.bumpNumberingRev();
        return view(repository.saveAndFlush(row));
    }

    /** Makes {@code nextNumber} the number the next person gets. Only forward: numbers are never reused. */
    public NumberingSettings advance(long nextNumber) {
        SchoolSettings row = settings.currentRow();
        boolean moved = sequences.advanceTo(schoolContext.currentSchoolId(), row.getNumberingPrefix(), yearKey(row), nextNumber);
        if (!moved) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR)
                    .with("errors", List.of(Map.of("field", "nextNumber", "rule", "NotBackwards")));
        }
        return view(row);
    }

    /**
     * What the next ID of each kind of person would look like. {@code candidate} lets the page preview edits that
     * are not saved yet; with null the saved settings are used. Uses nothing up.
     */
    public NumberingPreview preview(NumberingSettings candidate) {
        SchoolSettings row = settings.currentRow();
        NumberingSettings cfg = candidate == null ? view(row) : normalize(candidate);
        long next = sequences.peekNext(schoolContext.currentSchoolId(), cfg.prefix(), yearKey(cfg.includeYear()));
        return new NumberingPreview(format(cfg, StaffRole.STAFF, next), format(cfg, StaffRole.TEACHER, next),
                format(cfg, StaffRole.ADMIN, next));
    }

    // ------------------------------------------------------------------ the generator

    /** The one-argument form must also join the caller transaction (a default method would start its own). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(StaffRole role) {
        return next(role, id -> false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(StaffRole role, Predicate<String> alreadyUsed) {
        SchoolSettings row = settings.currentRow();
        NumberingSettings cfg = view(row);
        UUID school = schoolContext.currentSchoolId();
        for (int i = 0; i < MAX_SKIPPED; i++) {
            long number = sequences.take(school, cfg.prefix(), yearKey(cfg.includeYear()));
            String id = format(cfg, role, number);
            if (!alreadyUsed.test(id)) {
                return id;
            }
        }
        throw new IllegalStateException("No free Staff ID found after " + MAX_SKIPPED + " tries");
    }

    // ------------------------------------------------------------------ helpers

    /** Builds an ID from its parts. Pure, so the format can be tested on its own. */
    static String render(NumberingSettings cfg, StaffRole role, int year, long number) {
        List<String> parts = new ArrayList<>();
        parts.add(cfg.prefix());
        if (cfg.rolePrefix()) {
            parts.add(role.prefix());
        }
        if (cfg.includeYear()) {
            parts.add(String.format("%02d", year % 100));
        }
        parts.add(String.format("%0" + cfg.length() + "d", number));
        return String.join(cfg.separator(), parts);
    }

    private String format(NumberingSettings cfg, StaffRole role, long number) {
        return render(cfg, role, yearKey(true), number);
    }

    private int yearKey(SchoolSettings row) {
        return yearKey(row.isNumberingYear());
    }

    /** The year the counter is kept for: the school calendar year if the ID has a year part, otherwise 0. */
    private int yearKey(boolean includeYear) {
        return includeYear ? LocalDate.now(clock.withZone(settings.zone())).getYear() : 0;
    }

    private NumberingSettings view(SchoolSettings row) {
        long next = sequences.peekNext(schoolContext.currentSchoolId(), row.getNumberingPrefix(), yearKey(row));
        return new NumberingSettings(row.getNumberingPrefix(), row.getNumberingSeparator(), row.getNumberingLength(),
                row.isNumberingRolePrefix(), row.isNumberingYear(), next, row.getNumberingRev());
    }

    private static NumberingSettings normalize(NumberingSettings in) {
        List<Map<String, String>> errors = new ArrayList<>();
        String prefix = in.prefix().trim().toUpperCase();
        if (!prefix.matches("[A-Z0-9]{1,10}")) {
            errors.add(Map.of("field", "prefix", "rule", "Pattern"));
        }
        if (!in.separator().matches("[-_./]{0,3}")) {
            errors.add(Map.of("field", "separator", "rule", "Pattern"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
        }
        return new NumberingSettings(prefix, in.separator(), in.length(), in.rolePrefix(), in.includeYear(),
                in.nextNumber(), in.version());
    }
}
