package kh.cmt.auth.keys;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Creates the first signing key on a fresh database. */
@Component
@Order(1)
class SigningKeyBootstrap implements ApplicationRunner {

    private final SigningKeyService keys;

    SigningKeyBootstrap(SigningKeyService keys) {
        this.keys = keys;
    }

    @Override
    public void run(ApplicationArguments args) {
        keys.ensureActiveKey();
    }
}
