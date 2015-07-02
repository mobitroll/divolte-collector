package io.divolte.server.pubsub;

import io.divolte.server.AvroRecordBuffer;
import io.divolte.server.ValidatedConfiguration;
import io.divolte.server.processing.ProcessingPool;

import java.util.Objects;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class PubsubFlushingPool extends ProcessingPool<PubsubFlusher, AvroRecordBuffer> {
    public PubsubFlushingPool(final ValidatedConfiguration vc) {
        this(
                Objects.requireNonNull(vc),
                vc.configuration().kafkaFlusher.threads,
                vc.configuration().kafkaFlusher.maxWriteQueue,
                vc.configuration().kafkaFlusher.maxEnqueueDelay.toMillis()
        );
    }

    public PubsubFlushingPool(ValidatedConfiguration vc, int numThreads, int maxWriteQueue, long maxEnqueueDelay) {
        super(numThreads, maxWriteQueue, maxEnqueueDelay, "Pubsub Flusher", () -> new PubsubFlusher(vc));
    }

    public void enqueueRecord(final AvroRecordBuffer record) {
        enqueue(record.getPartyId().value, record);
    }
}
