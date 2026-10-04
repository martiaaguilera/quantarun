package io.github.martiaaguilera.quantarun.controlplane.jobs;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Telemetry about a state change must describe what was committed. A counter bumped inside a transaction that then
 * rolls back would count work that never happened, and a span ended there would show a placement that does not exist.
 */
final class AfterCommit {

    private AfterCommit() {}

    /** Runs {@code onCommit} once the current transaction commits, or at once when there is none. */
    static void run(Runnable onCommit) {
        run(onCommit, () -> {});
    }

    static void run(Runnable onCommit, Runnable onRollback) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            onCommit.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    onCommit.run();
                } else {
                    onRollback.run();
                }
            }
        });
    }
}
