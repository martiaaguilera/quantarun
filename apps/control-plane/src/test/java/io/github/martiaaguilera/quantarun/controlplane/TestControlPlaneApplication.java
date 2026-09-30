package io.github.martiaaguilera.quantarun.controlplane;

import org.springframework.boot.SpringApplication;

/** Runs the control plane locally against a throwaway PostgreSQL container, without docker compose. */
public class TestControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.from(ControlPlaneApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
