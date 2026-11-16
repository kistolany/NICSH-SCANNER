package kh.cmt.org.application;

import static org.assertj.core.api.Assertions.assertThat;

import kh.cmt.org.api.NumberingSettings;
import kh.cmt.org.api.StaffRole;
import org.junit.jupiter.api.Test;

/** The shape of a Staff ID, without any database. */
class StaffIdFormatTest {

    private static NumberingSettings cfg(String prefix, String sep, int len, boolean role, boolean year) {
        return new NumberingSettings(prefix, sep, len, role, year, null, 0L);
    }

    @Test
    void theDefaultLooksLikeTheDesign() {
        assertThat(NumberingService.render(cfg("CMT", "-", 4, false, false), StaffRole.STAFF, 2026, 151)).isEqualTo("CMT-0151");
    }

    @Test
    void roleAndYearPartsAreOptionalAndOrdered() {
        var all = cfg("CMT", "-", 4, true, true);
        assertThat(NumberingService.render(all, StaffRole.STAFF, 2026, 151)).isEqualTo("CMT-STF-26-0151");
        assertThat(NumberingService.render(all, StaffRole.TEACHER, 2026, 151)).isEqualTo("CMT-TCH-26-0151");
        assertThat(NumberingService.render(all, StaffRole.ADMIN, 2026, 7)).isEqualTo("CMT-ADM-26-0007");
        assertThat(NumberingService.render(cfg("CMT", "-", 4, true, false), StaffRole.TEACHER, 2026, 151)).isEqualTo("CMT-TCH-0151");
        assertThat(NumberingService.render(cfg("CMT", "-", 4, false, true), StaffRole.TEACHER, 2026, 151)).isEqualTo("CMT-26-0151");
    }

    @Test
    void separatorAndLengthFollowTheSettings() {
        assertThat(NumberingService.render(cfg("CMT", "", 4, false, false), StaffRole.STAFF, 2026, 5)).isEqualTo("CMT0005");
        assertThat(NumberingService.render(cfg("CMT", "/", 3, true, false), StaffRole.STAFF, 2026, 5)).isEqualTo("CMT/STF/005");
        assertThat(NumberingService.render(cfg("CMT", "_", 8, false, false), StaffRole.STAFF, 2026, 5)).isEqualTo("CMT_00000005");
    }

    @Test
    void aNumberLongerThanTheLengthIsNeverCutOff() {
        assertThat(NumberingService.render(cfg("CMT", "-", 4, false, false), StaffRole.STAFF, 2026, 123456)).isEqualTo("CMT-123456");
    }

    @Test
    void theYearIsTwoDigitsAlsoAroundTheTurnOfTheCentury() {
        var withYear = cfg("CMT", "-", 4, false, true);
        assertThat(NumberingService.render(withYear, StaffRole.STAFF, 2105, 1)).isEqualTo("CMT-05-0001");
        assertThat(NumberingService.render(withYear, StaffRole.STAFF, 2100, 1)).isEqualTo("CMT-00-0001");
    }
}
