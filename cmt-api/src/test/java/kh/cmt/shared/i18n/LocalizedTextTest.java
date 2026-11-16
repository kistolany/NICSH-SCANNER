package kh.cmt.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalizedTextTest {

    @Test
    void returnsRequestedLanguage() {
        LocalizedText t = new LocalizedText("គណិតវិទ្យា", "Math", "الرياضيات");
        assertThat(t.in("en")).isEqualTo("Math");
        assertThat(t.in("ar")).isEqualTo("الرياضيات");
        assertThat(t.in("km")).isEqualTo("គណិតវិទ្យា");
    }

    @Test
    void fallsBackToKhmerThenEnglishWhenMissing() {
        assertThat(new LocalizedText("km", "en", null).in("ar")).isEqualTo("km");
        assertThat(new LocalizedText(null, "en", null).in("ar")).isEqualTo("en");
        assertThat(new LocalizedText(" ", null, null).in("en")).isNull();
    }
}
