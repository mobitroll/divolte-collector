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

package io.divolte.server.hdfs;

import static io.divolte.server.hdfs.FileCreateAndSyncStrategy.HdfsOperationResult.*;
import io.divolte.server.AvroRecordBuffer;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.NotThreadSafe;

import org.apache.hadoop.fs.FileSystem;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

@NotThreadSafe
@ParametersAreNonnullByDefault
public class FlumeRollingFileStrategy implements FileCreateAndSyncStrategy {
    private static final Logger logger = LoggerFactory.getLogger(FlumeRollingFileStrategy.class);

    private final static long HDFS_RECONNECT_DELAY = 15000;

    private final static String INFLIGHT_EXTENSION = ".partial";

    private final static AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    private final int instanceNumber;
    private final String hostString;
    private final DateFormat datePartFormat = new SimpleDateFormat("yyyyLLddHHmmss");

    private final Schema schema;

    private final long syncEveryMillis;
    private final int syncEveryRecords;
    private final long newFileEveryMillis;

    private final String hdfsWorkingDir;
    private final String hdfsPublishDir;
    private final short hdfsReplication;

    private HadoopFile currentFile;
    private long lastFixAttempt;

    public FlumeRollingFileStrategy(final Config config, final FileSystem fs, final short hdfsReplication, final Schema schema) {
        Objects.requireNonNull(config);
        this.schema = Objects.requireNonNull(schema);

        syncEveryMillis = config.getDuration("divolte.hdfs_flusher.simple_rolling_file_strategy.sync_file_after_duration", TimeUnit.MILLISECONDS);
        syncEveryRecords = config.getInt("divolte.hdfs_flusher.simple_rolling_file_strategy.sync_file_after_records");
        newFileEveryMillis = config.getDuration("divolte.hdfs_flusher.simple_rolling_file_strategy.roll_every", TimeUnit.MILLISECONDS);

        instanceNumber = INSTANCE_COUNTER.incrementAndGet();
        hostString = findLocalHostName();

        this.hdfsReplication = hdfsReplication;

        hdfsWorkingDir = config.getString("divolte.hdfs_flusher.simple_rolling_file_strategy.working_dir");
        hdfsPublishDir = config.getString("divolte.hdfs_flusher.simple_rolling_file_strategy.publish_dir");

        throwsIoException(() -> {
            if (!new File(hdfsWorkingDir).isDirectory()) {
                throw new IOException("Working directory for in-flight AVRO records does not exist: " + hdfsWorkingDir);
            }
            if (!new File(hdfsPublishDir).isDirectory()) {
                throw new IOException("Working directory for publishing AVRO records does not exist: " + hdfsPublishDir);
            }
        }).ifPresent((e) -> { throw new RuntimeException("Configuration error", e); });
    }

    private File newFilePath() {
        return new File(hdfsWorkingDir, String.format("%s-divolte-tracking-%s-%d.avro" + INFLIGHT_EXTENSION, datePartFormat.format(new Date()), hostString, instanceNumber));
    }

    private static String findLocalHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    private HadoopFile openNewFile(final File path) throws IOException  {
        return new HadoopFile(path);
    }

    @Override
    public HdfsOperationResult setup() {
        final File newFilePath = newFilePath();

        try {
        currentFile = openNewFile(newFilePath);
        logger.debug("Created new file in setup: {}", newFilePath.toString());
        return SUCCESS;
        } catch (IOException e) {
        logger.debug("Created new file in setup: {} failed", newFilePath.toString());
        return FAILURE;
        }
    }

    @Override
    public HdfsOperationResult heartbeat() {
        return SUCCESS;
    }

    @Override
    public HdfsOperationResult append(final AvroRecordBuffer record) {
        return throwsIoException(() -> {
            currentFile.writer.appendEncoded(record.getByteBuffer());
            currentFile.recordsSinceLastSync += 1;
            possiblySync();
        }).map((ioe) -> {
            logger.warn("Failed to flush event to HDFS.", ioe);
            return FAILURE;
        }).orElse(SUCCESS);
    }

    @Override
    public void cleanup() {
        throwsIoException(currentFile::close)
        .ifPresent((ioe) -> logger.warn("Failed to close HDFS file on flusher cleanup.", ioe));
    }

    private void possiblySync() throws IOException {
        final long time = System.currentTimeMillis();

        if (
                currentFile.recordsSinceLastSync >= syncEveryRecords ||
                time - currentFile.lastSyncTime >= syncEveryMillis && currentFile.recordsSinceLastSync > 0) {
            logger.debug("Syncing HDFS file: {}", currentFile.path.toString());

            // Forces the Avro file to write a block
            currentFile.writer.sync();
            // Forces a (HDFS) sync on the underlying stream
            currentFile.stream.flush();

            currentFile.totalRecords += currentFile.recordsSinceLastSync;
            currentFile.recordsSinceLastSync = 0;
            currentFile.lastSyncTime = time;
            possiblyRollFile(time);
        } else if (currentFile.recordsSinceLastSync == 0) {
            currentFile.lastSyncTime = time;
            possiblyRollFile(time);
        }
    }

    private void possiblyRollFile(final long time) throws IOException {
        if (time > currentFile.projectedCloseTime) {
            currentFile.close();

            logger.debug("Rolling file. Closed: {}", currentFile.path);

            final File newFilePath = newFilePath();
            try {
                currentFile = openNewFile(newFilePath);
            } catch (IOException e) {
                throwsIoException(() -> newFilePath.delete());
                throw e;
            }
            logger.debug("Rolling file. Opened: {}", currentFile.path);
        }
    }

    private HdfsOperationResult possiblyFixHdfsConnection() {
        final long time = System.currentTimeMillis();
        if (time - lastFixAttempt > HDFS_RECONNECT_DELAY) {
            final File newFilePath = newFilePath();
            return throwsIoException(() ->
                currentFile = openNewFile(newFilePath)
            ).map((ioe) -> {
                lastFixAttempt = time;
                // possibly we created the file, so silently attempt a delete
                throwsIoException(() -> newFilePath.delete());
                return FAILURE;
            }).orElseGet(() -> {
                lastFixAttempt = 0;
                logger.info("Recovered HDFS connection.");
                return SUCCESS;
            });
        } else {
            return FAILURE;
        }
    }

    private final class HadoopFile implements AutoCloseable {
        final long openTime;
        final long projectedCloseTime;
        final File path;
        final FileOutputStream stream;
        final DataFileWriter<GenericRecord> writer;

        long lastSyncTime;
        int recordsSinceLastSync;
        long totalRecords;

        @SuppressWarnings("resource")
        public HadoopFile(File path) throws IOException {
            this.path = path;
            this.stream = new FileOutputStream(File.createTempFile("divolte-", ".avro", path));

            writer = new DataFileWriter<GenericRecord>(new GenericDatumWriter<>(schema)).create(schema, stream);
            writer.setSyncInterval(1 << 30);
            writer.setFlushOnEveryBlock(true);

            // Sync the file on open to make sure the
            // connection actually works, because
            // HDFS allows file creation even with no
            // datanodes available
            this.stream.flush();

            this.openTime = this.lastSyncTime = System.currentTimeMillis();
            this.recordsSinceLastSync = 0;
            this.totalRecords = 0;
            this.projectedCloseTime = openTime + newFileEveryMillis;
        }

        private File getPublishDestination() {
            final String pathName = path.getName();
            return new File(hdfsPublishDir, pathName.substring(0, pathName.length() - INFLIGHT_EXTENSION.length()));
        }

        public void close() throws IOException {
            totalRecords += recordsSinceLastSync;
            writer.close();

            if (totalRecords > 0) {
                // Publish file to destination directory
                final File publishDestination = getPublishDestination();
                logger.debug("Moving HDFS file: {} -> {}", path, publishDestination);
                if (!path.renameTo(publishDestination)) {
                    throw new IOException("Could not rename HDFS file: " + path + " -> " + publishDestination);
                }
            } else {
                // Discard empty file
                logger.debug("Deleting empty HDFS file: {}", path);
                throwsIoException(() -> path.delete())
                .ifPresent((ioe) -> logger.warn("Could not delete empty HDFS file.", ioe));
            }
        }
    }

    @FunctionalInterface
    private interface IOExceptionThrower {
        public abstract void run() throws IOException;
    }

    private static Optional<IOException> throwsIoException(final IOExceptionThrower r) {
        try {
            r.run();
            return Optional.empty();
        } catch (final IOException ioe) {
            return Optional.of(ioe);
        }
    }
}
