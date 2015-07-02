package io.divolte.server.pubsub;

import com.google.api.services.pubsub.Pubsub;
import com.google.api.services.pubsub.model.PublishRequest;
import com.google.api.services.pubsub.model.PubsubMessage;
import io.divolte.server.AvroRecordBuffer;
import io.divolte.server.ValidatedConfiguration;
import io.divolte.server.processing.ItemProcessor;
import io.divolte.server.pubsub.util.PortableConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

import static io.divolte.server.processing.ItemProcessor.ProcessingDirective.CONTINUE;
import static io.divolte.server.processing.ItemProcessor.ProcessingDirective.PAUSE;

@ParametersAreNonnullByDefault
@NotThreadSafe
public class PubsubFlusher implements ItemProcessor<AvroRecordBuffer> {
    private final static Logger logger = LoggerFactory.getLogger(PubsubFlusher.class);

    private final Pubsub producer;
    private final String fullTopicName;

    // On failure, we pause delivery and store the failed operation here.
    // During heartbeats it will be retried until success.
    private Optional<PubsubSender> pendingOperation = Optional.empty();

    public PubsubFlusher(final ValidatedConfiguration vc) {
        Objects.requireNonNull(vc);
        fullTopicName = String.format("projects/%s/topics/%s",
                vc.configuration().pubsubFlusher.project,
                vc.configuration().pubsubFlusher.topic);
        producer = PortableConfiguration.createPubsubClient(
                vc.configuration().pubsubFlusher.credentials,
                vc.configuration().pubsubFlusher.applicationName).orElseThrow(() ->
           new RuntimeException("Couldn't initialize Pubsub client.")
        );
    }

    @Override
    public ProcessingDirective process(AvroRecordBuffer record) {
        logger.debug("Processing individual record.", record);
        return send(() -> {
            List<PubsubMessage> pubsubMessages = new ArrayList<>(1);
            pubsubMessages.add(buildMessage(record));
            executePublish(pubsubMessages);
            logger.debug("Sent individual record to Pubsub.", record);
        });
    }

    @Override
    public ProcessingDirective process(Queue<AvroRecordBuffer> batch) {
        final int batchSize = batch.size();
        final ProcessingDirective result;
        switch (batchSize) {
            case 0:
                logger.warn("Ignoring empty batch of events.");
                result = CONTINUE;
                break;
            case 1:
                result = process(batch.remove());
                break;
            default:
                logger.debug("Processing batch of {} records.", batchSize);
                final List<PubsubMessage> pubsubMessages =
                        batch.stream()
                                .map(this::buildMessage)
                                .collect(Collectors.toCollection(() -> new ArrayList<>(batchSize)));
                // Clear the messages now; on failure they'll be retried as part of our
                // pending operation.
                batch.clear();
                result = send(() -> {
                    executePublish(pubsubMessages);
                    logger.debug("Sent {} records to Pubsub.", batchSize);
                });
        }
        return result;
    }

    @Override
    public ProcessingDirective heartbeat() {
        return pendingOperation.map((t) -> {
            logger.debug("Retrying to send message(s) that failed.");
            return send(t);
        }).orElse(CONTINUE);
    }

    @FunctionalInterface
    private interface PubsubSender {
        void send() throws IOException;
    }

    private ProcessingDirective send(final PubsubSender sender) {
        ProcessingDirective result;
        try {
            sender.send();
            pendingOperation = Optional.empty();
            result = CONTINUE;
        } catch (final IOException e) {
            logger.warn("Failed to send message(s) to Pubsub! (Will retry.)", e);
            pendingOperation = Optional.of(sender);
            result = PAUSE;
        }
        return result;
    }

    private PubsubMessage buildMessage(final AvroRecordBuffer record) {
        // Extract the AVRO record as a byte array.
        // (There's no way to do this without copying the array.)
        final ByteBuffer avroBuffer = record.getByteBuffer();
        final byte[] avroBytes = new byte[avroBuffer.remaining()];
        avroBuffer.get(avroBytes);
        PubsubMessage pubsubMessage = new PubsubMessage();
        pubsubMessage.encodeData(avroBytes);
        return pubsubMessage;
    }

    private void executePublish(List<PubsubMessage> messageList) throws IOException {
        PublishRequest publishRequest = new PublishRequest();
        publishRequest.setMessages(messageList);
        logger.debug("Sending message(s) to {}", fullTopicName);
        producer.projects().topics()
                .publish(fullTopicName, publishRequest)
                .execute();
    }
}
