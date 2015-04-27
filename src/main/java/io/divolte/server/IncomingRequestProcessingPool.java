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

package io.divolte.server;

import io.divolte.record.DefaultEventRecord;
import io.divolte.server.CookieValues.CookieValue;
import io.divolte.server.hdfs.HdfsFlushingPool;
import io.divolte.server.ip2geo.ExternalDatabaseLookupService;
import io.divolte.server.ip2geo.LookupService;
import io.divolte.server.kafka.KafkaFlushingPool;
import io.divolte.server.flume.FlumeFlushingPool;
import io.divolte.server.processing.ProcessingPool;
import io.undertow.server.HttpServerExchange;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

@ParametersAreNonnullByDefault
final class IncomingRequestProcessingPool extends ProcessingPool<IncomingRequestProcessor, HttpServerExchange> {
    private final static Logger logger = LoggerFactory.getLogger(IncomingRequestProcessingPool.class);

    private final Optional<KafkaFlushingPool> kafkaPool;
    private final Optional<FlumeFlushingPool> flumePool;
    private final Optional<HdfsFlushingPool> hdfsPool;

    public IncomingRequestProcessingPool() {
        this(ConfigFactory.load(), (e,b,r) -> {});
    }

    public IncomingRequestProcessingPool(final Config config, IncomingRequestListener listener) {
        this (
                config.getInt("divolte.incoming_request_processor.threads"),
                config.getInt("divolte.incoming_request_processor.max_write_queue"),
                config.getDuration("divolte.incoming_request_processor.max_enqueue_delay", TimeUnit.MILLISECONDS),
                config,
                schemaFromConfig(config),
                config.getBoolean("divolte.kafka_flusher.enabled") ? new KafkaFlushingPool(config) : null,
                config.getBoolean("divolte.flume_flusher.enabled") ? new FlumeFlushingPool(config, schemaFromConfig(config)) : null,
                config.getBoolean("divolte.hdfs_flusher.enabled") ? new HdfsFlushingPool(config, schemaFromConfig(config)) : null,
                lookupServiceFromConfig(config),
                listener
                );
    }

    public IncomingRequestProcessingPool(
            final int numThreads,
            final int maxQueueSize,
            final long maxEnqueueDelay,
            final Config config,
            final Schema schema,
            @Nullable final KafkaFlushingPool kafkaFlushingPool,
            @Nullable final FlumeFlushingPool flumeFlushingPool,
            @Nullable final HdfsFlushingPool hdfsFlushingPool,
            @Nullable final LookupService geoipLookupService,
            final IncomingRequestListener listener) {
        super(
                numThreads,
                maxQueueSize,
                maxEnqueueDelay,
                "Incoming Request Processor",
                () -> new IncomingRequestProcessor(config, kafkaFlushingPool, flumeFlushingPool, hdfsFlushingPool, geoipLookupService, schema, listener));

        this.kafkaPool = Optional.ofNullable(kafkaFlushingPool);
        this.flumePool = Optional.ofNullable(flumeFlushingPool);
        this.hdfsPool = Optional.ofNullable(hdfsFlushingPool);
    }

    private static Schema schemaFromConfig(final Config config) {
        final Schema schema;
        try {
            if (config.hasPath("divolte.tracking.schema_file")) {
                final Parser parser = new Schema.Parser();
                final String schemaFileName = config.getString("divolte.tracking.schema_file");
                logger.info("Using Avro schema from configuration: {}", schemaFileName);
                schema = parser.parse(new File(schemaFileName));
            } else {
                logger.info("Using built in default Avro schema.");
                schema = DefaultEventRecord.getClassSchema();
            }
        } catch(IOException ioe) {
            logger.error("Failed to load Avro schema file.");
            throw new RuntimeException("Failed to load Avro schema file.", ioe);
        }
        return schema;
    }

    @Nullable
    private static LookupService lookupServiceFromConfig(final Config config) {
        return OptionalConfig.of(config::getString, "divolte.tracking.ip2geo_database")
                .map((path) -> {
                    try {
                        return new ExternalDatabaseLookupService(Paths.get(path));
                    } catch (final IOException e) {
                        logger.error("Failed to configure GeoIP database: " + path, e);
                        throw new RuntimeException("Failed to configure GeoIP lookup service.", e);
                    }
                }).orElse(null);
    }

    public void enqueueIncomingExchangeForProcessing(final CookieValue partyId, final HttpServerExchange exchange) {
        enqueue(partyId.value, exchange);
    }

    @Override
    public void stop() {
        super.stop();

        kafkaPool.ifPresent(KafkaFlushingPool::stop);
        flumePool.ifPresent(FlumeFlushingPool::stop);
        hdfsPool.ifPresent(HdfsFlushingPool::stop);
    }
}
