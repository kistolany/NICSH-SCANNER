package kh.cmt.architecture;

import kh.cmt.CmtApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Fails the build when a module reaches into another module's internals or modules depend in a cycle. */
class ModulithVerificationTest {

    @Test
    void moduleBoundariesAreRespected() {
        ApplicationModules.of(CmtApplication.class).verify();
    }
}
