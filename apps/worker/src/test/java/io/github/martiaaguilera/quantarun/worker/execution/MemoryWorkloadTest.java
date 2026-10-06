package io.github.martiaaguilera.quantarun.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MemoryWorkloadTest {

    @Test
    void anAllocationBeyondTheBudget_isRefusedBeforeAnythingIsAllocated() {
        var workload = new MemoryWorkload(8);

        assertThatThrownBy(() -> workload.execute(
                        new Payload(Map.of("mib", 16, "holdMs", 0)), AttemptContext.withoutCheckpoints(1)))
                .isInstanceOfSatisfying(
                        WorkloadFailure.class,
                        failure -> assertThat(failure.failureClass()).isEqualTo(FailureClass.RESOURCE_EXHAUSTED));
        assertThat(workload.heldMib()).isZero();
    }

    @Test
    void theBudgetIsReleased_whenAnAttemptEnds() throws Exception {
        var workload = new MemoryWorkload(8);

        workload.execute(new Payload(Map.of("mib", 6, "holdMs", 0)), AttemptContext.withoutCheckpoints(1));
        var second = workload.execute(new Payload(Map.of("mib", 6, "holdMs", 0)), AttemptContext.withoutCheckpoints(1));

        assertThat(second).containsEntry("allocatedMib", 6);
        assertThat(workload.heldMib()).isZero();
    }

    @Test
    void concurrentReservations_neverOvershootTheBudget() throws Exception {
        var workload = new MemoryWorkload(8);
        var start = new CountDownLatch(1);
        var granted = new AtomicInteger();
        try (var threads = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 64; i++) {
                threads.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (workload.tryReserve(1)) {
                        granted.incrementAndGet();
                    }
                });
            }
            start.countDown();
        }

        assertThat(granted.get()).isEqualTo(8);
        assertThat(workload.heldMib()).isEqualTo(8);
    }

    /**
     * The worker image runs with {@code -XX:+ExitOnOutOfMemoryError}: a heap OutOfMemoryError ends the JVM before any
     * catch block runs. A job asking for more than the heap must fail as RESOURCE_EXHAUSTED and leave the worker alive.
     */
    @Test
    void underTheImagesJvmFlags_aJobLargerThanTheHeap_failsWithoutEndingTheWorker() throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ArrayList<String>();
        command.add(java);
        command.add("-Xmx64m");
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Probe.class.getName());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var finished = process.waitFor(60, TimeUnit.SECONDS);
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(finished).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("RESOURCE_EXHAUSTED").doesNotContain("Terminating due to");
    }

    /** Runs in the child JVM: asks for twice its 64 MiB heap. */
    static final class Probe {
        public static void main(String[] args) throws Exception {
            try {
                new MemoryWorkload()
                        .execute(new Payload(Map.of("mib", 128, "holdMs", 0)), AttemptContext.withoutCheckpoints(1));
                System.out.println("ALLOCATED");
            } catch (WorkloadFailure failure) {
                System.out.println(failure.failureClass());
            }
        }
    }
}
