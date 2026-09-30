package io.github.martiaaguilera.quantarun.controlplane;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleStructureTest {

    @Test
    void moduleBoundaries_haveNoCyclesAndNoAccessToInternals() {
        ApplicationModules.of(ControlPlaneApplication.class).verify();
    }
}
