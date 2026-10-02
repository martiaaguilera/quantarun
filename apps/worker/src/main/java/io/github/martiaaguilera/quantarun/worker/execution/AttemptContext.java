package io.github.martiaaguilera.quantarun.worker.execution;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a workload knows about the attempt it runs in.
 *
 * @param attemptNo 1-based attempt number across the job's whole history.
 * @param lastCheckpoint the job's last committed stage; a staged workload resumes after it.
 * @param checkpoints commits a stage result; fenced by the control plane like a report.
 */
record AttemptContext(int attemptNo, WorkerProtocol.@Nullable Checkpoint lastCheckpoint, CheckpointSink checkpoints) {

    /** Commits one stage. Throws {@link AttemptFencedException} when the control plane no longer accepts it. */
    @FunctionalInterface
    interface CheckpointSink {
        void commit(int stageIndex, Map<String, Object> result) throws InterruptedException;
    }

    /** For workloads that never checkpoint, and for tests. */
    static AttemptContext withoutCheckpoints(int attemptNo) {
        return new AttemptContext(attemptNo, null, (stage, result) -> {
            throw new IllegalStateException("this workload does not checkpoint");
        });
    }
}
