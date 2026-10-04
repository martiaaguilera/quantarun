package io.github.martiaaguilera.quantarun.controlplane;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Keeps the spans the application exports, so tests can assert on traces. Bounded: the context is shared by the whole
 * suite, so only the most recent spans are kept.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CollectedSpans {

    static final int CAPACITY = 20_000;

    public static final class Exporter implements SpanExporter {

        private final ArrayDeque<SpanData> spans = new ArrayDeque<>();
        private final ObjectProvider<SdkTracerProvider> tracerProvider;

        Exporter(ObjectProvider<SdkTracerProvider> tracerProvider) {
            this.tracerProvider = tracerProvider;
        }

        @Override
        public synchronized CompletableResultCode export(Collection<SpanData> batch) {
            for (var span : batch) {
                if (spans.size() == CAPACITY) {
                    spans.removeFirst();
                }
                spans.addLast(span);
            }
            return CompletableResultCode.ofSuccess();
        }

        /** Every finished span of one trace, after pushing out what the batch processor still holds. */
        public List<SpanData> trace(String traceId) {
            tracerProvider.getObject().forceFlush().join(10, TimeUnit.SECONDS);
            synchronized (this) {
                return spans.stream()
                        .filter(span -> span.getTraceId().equals(traceId))
                        .toList();
            }
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    @Bean
    Exporter collectedSpans(ObjectProvider<SdkTracerProvider> tracerProvider) {
        return new Exporter(tracerProvider);
    }
}
