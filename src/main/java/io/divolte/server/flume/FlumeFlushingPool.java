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
import io.divolte.server.processing.ProcessingPool;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.annotation.ParametersAreNonnullByDefault;

import com.typesafe.config.Config;

import org.apache.avro.Schema;

@ParametersAreNonnullByDefault
public class FlumeFlushingPool extends ProcessingPool<FlumeFlusher, AvroRecordBuffer> {
    public FlumeFlushingPool(final Config config, final Schema schema) {
        this(
                Objects.requireNonNull(config),
                Objects.requireNonNull(schema),
                config.getInt("divolte.flume_flusher.threads"),
                config.getInt("divolte.flume_flusher.max_write_queue"),
                config.getDuration("divolte.flume_flusher.max_enqueue_delay", TimeUnit.MILLISECONDS)
                );
    }

    public FlumeFlushingPool(Config config, final Schema schema, int numThreads, int maxWriteQueue, long maxEnqueueDelay) {
          super(numThreads, maxWriteQueue, maxEnqueueDelay, "Flume Flusher", () -> new FlumeFlusher(config, schema));
    }

    public void enqueueRecord(final AvroRecordBuffer record) {
        enqueue(record.getPartyId().value, record);
    }
}
