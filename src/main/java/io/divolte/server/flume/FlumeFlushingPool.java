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
import io.divolte.server.ValidatedConfiguration;
import io.divolte.server.processing.ProcessingPool;
import org.apache.avro.Schema;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;

@ParametersAreNonnullByDefault
public class FlumeFlushingPool extends ProcessingPool<FlumeFlusher, AvroRecordBuffer> {
    public FlumeFlushingPool(final ValidatedConfiguration config, final Schema schema) {
        this(
                Objects.requireNonNull(config),
                Objects.requireNonNull(schema),
                config.configuration().flumeFlusher.threads,
                config.configuration().flumeFlusher.maxWriteQueue,
                config.configuration().flumeFlusher.maxEnqueueDelay.toMillis()
                );
    }

    public FlumeFlushingPool(final ValidatedConfiguration config, final Schema schema, int numThreads, int maxWriteQueue, long maxEnqueueDelay) {
          super(numThreads, maxWriteQueue, maxEnqueueDelay, "Flume Flusher", () -> new FlumeFlusher(config, schema));
    }

    public void enqueueRecord(final AvroRecordBuffer record) {
        enqueue(record.getPartyId().value, record);
    }
}
