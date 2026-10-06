package io.github.martiaaguilera.quantarun.controlplane;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Java-side time that a test moves by hand, so no test sleeps through a grace period or a gap. */
public final class SettableClock extends Clock {

    private volatile Instant now;

    public SettableClock(Instant now) {
        this.now = now;
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
