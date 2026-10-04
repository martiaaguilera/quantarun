/**
 * The pure scheduling policies (ADR-0004). Exposed as a named interface because the simulator runs exactly this code:
 * anything measured in simulation is measured on the planner the live scheduler uses.
 */
@NamedInterface("policy")
package io.github.martiaaguilera.quantarun.controlplane.scheduler.policy;

import org.springframework.modulith.NamedInterface;
