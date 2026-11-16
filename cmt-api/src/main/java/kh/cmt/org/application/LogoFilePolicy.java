package kh.cmt.org.application;

import java.util.UUID;
import kh.cmt.files.api.FileReadPolicy;
import kh.cmt.shared.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The school logo may be opened by everybody of the school (it is on the app, the cards, the reports). */
@Component
class LogoFilePolicy implements FileReadPolicy {

    private final JdbcClient jdbc;

    LogoFilePolicy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Decision decide(UUID fileId, CurrentUser caller) {
        boolean logo = jdbc.sql("select count(*) from school_settings where school_id = :s and logo_file_id = :f")
                .param("s", caller.schoolId()).param("f", fileId).query(Integer.class).single() > 0;
        return logo ? Decision.ALLOW : Decision.ABSTAIN;
    }
}
