/*
 * Copyright 2014 GoDataDriven B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.divolte.server.flume;

import io.divolte.server.AvroRecordBuffer;
import io.divolte.server.processing.ItemProcessor;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Queue;
import java.util.stream.Collectors;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.NotThreadSafe;

import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.api.RpcClient;
import org.apache.flume.api.RpcClientFactory;
import org.apache.flume.event.EventBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.io.EncoderFactory;

import com.google.common.base.Joiner;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigValue;

import static io.divolte.server.processing.ItemProcessor.ProcessingDirective.*;

@ParametersAreNonnullByDefault
@NotThreadSafe
public final class FlumeFlusher implements ItemProcessor<AvroRecordBuffer> {
    private final static Logger logger = LoggerFactory.getLogger(FlumeFlusher.class);

    // On failure, we pause delivery and store the failed operation here.
    // During heartbeats it will be retried until success.
    private Optional<FlumeSender> pendingOperation = Optional.empty();

    RpcClient client;
    final String hostname;
    final int port;
    final Schema schema;

    public FlumeFlusher(final Config config, final Schema schema) {
        Objects.requireNonNull(config);
        
        hostname = config.getString("divolte.flume_flusher.hostname");
        port = config.getInt("divolte.flume_flusher.port");

        this.schema = schema;
      
        try {
            client = RpcClientFactory.getDefaultInstance(hostname, port);
        } catch (Exception e) {
            logger.info("Couldn't connect to Flume at startup, will retry");
            client = null;
        }
    }

    private void sendDataToFlume(final AvroRecordBuffer record) {
      if (client == null) {
          try {
              client = RpcClientFactory.getDefaultInstance(hostname, port);
          } catch (Exception e) {
              logger.info("Couldn't connect to Flume at send, will retry");
              client = null;
              throw new RuntimeException("Error connecting");
          }
      }
      // Create a Flume Event object that encapsulates the sample data
      
      try {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        JsonEncoder enc = EncoderFactory.get().jsonEncoder(schema, outputStream);
      
        DataFileWriter<GenericRecord> writer = new DataFileWriter<GenericRecord>(new GenericDatumWriter<>(schema)).create(schema, outputStream);
        writer.appendEncoded(record.getByteBuffer());
        writer.close();
        enc.flush();

        Event event = EventBuilder.withBody(outputStream.toByteArray());

        // Send the event
        try {
          client.append(event);
        } catch (EventDeliveryException e) {
          // clean up and recreate the client
          client.close();
          client = null;
          client = RpcClientFactory.getDefaultInstance(hostname, port);
        }
      } catch (IOException e) {
        throw new RuntimeException("Error serializing event");
      }
    }
    
    @Override
    public ProcessingDirective process(final AvroRecordBuffer record) {
        logger.debug("Processing individual record.", record);
        return send(() -> {
            sendDataToFlume(record);
            logger.debug("Sent individual record to flume.", record);
        });
    }

    @Override
    public ProcessingDirective heartbeat() {
        return pendingOperation.map((t) -> {
            logger.debug("Retrying to send message(s) that failed.");
            return send(t);
        }).orElse(CONTINUE);
    }

    @FunctionalInterface
    private interface FlumeSender {
        public abstract void send() throws Exception;
    }

    private ProcessingDirective send(final FlumeSender sender) {
        ProcessingDirective result;
        try {
            sender.send();
            pendingOperation = Optional.empty();
            result = CONTINUE;
        } catch (final Exception e) {
            logger.warn("Failed to send message(s) to flume! (Will retry.)", e);
            pendingOperation = Optional.of(sender);
            result = PAUSE;
        }
        return result;
    }
}
