/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.streamprocessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.camunda.zeebe.db.ZeebeDb;
import io.camunda.zeebe.db.ZeebeDbFactory;
import io.camunda.zeebe.engine.Loggers;
import io.camunda.zeebe.engine.api.CommandResponseWriter;
import io.camunda.zeebe.engine.api.EmptyProcessingResult;
import io.camunda.zeebe.engine.api.InterPartitionCommandSender;
import io.camunda.zeebe.engine.api.RecordProcessor;
import io.camunda.zeebe.engine.api.StreamProcessorLifecycleAware;
import io.camunda.zeebe.engine.state.appliers.EventAppliers;
import io.camunda.zeebe.engine.util.RecordToWrite;
import io.camunda.zeebe.logstreams.impl.log.LoggedEventImpl;
import io.camunda.zeebe.logstreams.log.LogStreamBatchWriter;
import io.camunda.zeebe.logstreams.log.LogStreamReader;
import io.camunda.zeebe.logstreams.log.LoggedEvent;
import io.camunda.zeebe.logstreams.util.ListLogStorage;
import io.camunda.zeebe.logstreams.util.SyncLogStream;
import io.camunda.zeebe.logstreams.util.SynchronousLogStream;
import io.camunda.zeebe.scheduler.Actor;
import io.camunda.zeebe.scheduler.ActorScheduler;
import io.camunda.zeebe.scheduler.future.ActorFuture;
import io.camunda.zeebe.util.FileUtil;
import io.camunda.zeebe.util.buffer.BufferUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;

public final class StreamPlatform {

  private static final String SNAPSHOT_FOLDER = "snapshot";
  private static final Logger LOG = Loggers.STREAM_PROCESSING;
  private static final int DEFAULT_PARTITION = 1;
  private static final String STREAM_NAME = "stream-";

  private final Path dataDirectory;
  private final List<AutoCloseable> closeables;
  private final ActorScheduler actorScheduler;
  private final CommandResponseWriter mockCommandResponseWriter;
  private LogContext logContext;
  private ProcessorContext processorContext;
  private boolean snapshotWasTaken = false;
  private final StreamProcessorMode streamProcessorMode = StreamProcessorMode.PROCESSING;
  private List<RecordProcessor> recordProcessors;

  private final RecordProcessor defaultMockedRecordProcessor;

  private final WriteActor writeActor = new WriteActor();
  private final ZeebeDbFactory zeebeDbFactory;
  private final StreamProcessorLifecycleAware mockProcessorLifecycleAware;
  private final StreamProcessorListener mockStreamProcessorListener;

  public StreamPlatform(
      final Path dataDirectory,
      final List<AutoCloseable> closeables,
      final ActorScheduler actorScheduler,
      final ZeebeDbFactory zeebeDbFactory) {
    this.dataDirectory = dataDirectory;
    this.closeables = closeables;
    this.actorScheduler = actorScheduler;
    this.zeebeDbFactory = zeebeDbFactory;

    mockCommandResponseWriter = mock(CommandResponseWriter.class);
    when(mockCommandResponseWriter.intent(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.key(anyLong())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.partitionId(anyInt())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.recordType(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.rejectionType(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.rejectionReason(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.valueType(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.valueWriter(any())).thenReturn(mockCommandResponseWriter);

    when(mockCommandResponseWriter.tryWriteResponse(anyInt(), anyLong())).thenReturn(true);
    actorScheduler.submitActor(writeActor);

    defaultMockedRecordProcessor = mock(RecordProcessor.class);
    when(defaultMockedRecordProcessor.process(any(), any()))
        .thenReturn(EmptyProcessingResult.INSTANCE);
    when(defaultMockedRecordProcessor.onProcessingError(any(), any(), any()))
        .thenReturn(EmptyProcessingResult.INSTANCE);
    when(defaultMockedRecordProcessor.accepts(any())).thenReturn(true);
    recordProcessors = List.of(defaultMockedRecordProcessor);
    closeables.add(() -> recordProcessors.clear());
    mockProcessorLifecycleAware = mock(StreamProcessorLifecycleAware.class);
    mockStreamProcessorListener = mock(StreamProcessorListener.class);

    logContext = createLogContext(new ListLogStorage(), DEFAULT_PARTITION);
    closeables.add(logContext);
  }

  public CommandResponseWriter getMockCommandResponseWriter() {
    return mockCommandResponseWriter;
  }

  /**
   * This can be used to overwrite the current logContext. In some tests useful, were we need more
   * control about the backend.
   *
   * <p>Note: The previous logContext will be overwritten, but it is still be part of the closables
   * list which means it will be closed at the end.
   */
  void setLogContext(final LogContext logContext) {
    this.logContext = logContext;
    closeables.add(logContext);
  }

  /**
   * Creates a LogContext, which consist of given logStorage and a LogStream for the given
   * parititon.
   *
   * <p>Note: Make sure to close the LogContext, to not leak any memory.
   *
   * @param logStorage the list logstorage which should be used as backend
   * @param partitionId the partition ID for the log stream
   * @return the create log context
   */
  public LogContext createLogContext(final ListLogStorage logStorage, final int partitionId) {
    final var logStream =
        SyncLogStream.builder()
            .withLogName(STREAM_NAME + partitionId)
            .withLogStorage(logStorage)
            .withPartitionId(partitionId)
            .withActorSchedulingService(actorScheduler)
            .build();

    logStorage.setPositionListener(logStream::setLastWrittenPosition);
    return new LogContext(logStream);
  }

  public SynchronousLogStream getLogStream() {
    return logContext.logStream();
  }

  public Stream<LoggedEvent> events() {
    final SynchronousLogStream logStream = getLogStream();

    final LogStreamReader reader = logStream.newLogStreamReader();
    closeables.add(reader);

    reader.seekToFirstEvent();

    final Iterable<LoggedEvent> iterable = () -> reader;

    // copy to allow for collecting, which is what AssertJ does under the hood when using stream
    // assertions
    return StreamSupport.stream(iterable.spliterator(), false)
        .map(
            event -> {
              final var copyableEvent = (LoggedEventImpl) event;
              final var copiedBuffer =
                  BufferUtil.cloneBuffer(
                      copyableEvent.getBuffer(),
                      copyableEvent.getFragmentOffset(),
                      copyableEvent.getLength());
              final var copy = new LoggedEventImpl();
              copy.wrap(copiedBuffer, 0);

              return copy;
            });
  }

  public Path createRuntimeFolder(final SynchronousLogStream stream) {
    final Path rootDirectory = dataDirectory.resolve(stream.getLogName()).resolve("state");

    try {
      Files.createDirectories(rootDirectory);
    } catch (final FileAlreadyExistsException ignored) {
      // totally fine if it already exists
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return rootDirectory.resolve("runtime");
  }

  public StreamPlatform withRecordProcessors(final List<RecordProcessor> recordProcessors) {
    this.recordProcessors = recordProcessors;
    return this;
  }

  public StreamProcessorListener getMockStreamProcessorListener() {
    return mockStreamProcessorListener;
  }

  public StreamProcessor startStreamProcessor() {
    final SynchronousLogStream stream = getLogStream();
    return buildStreamProcessor(stream, true);
  }

  public StreamProcessor startStreamProcessorNotAwaitOpening() {
    final SynchronousLogStream stream = getLogStream();
    return buildStreamProcessor(stream, false);
  }

  public StreamProcessorLifecycleAware getMockProcessorLifecycleAware() {
    return mockProcessorLifecycleAware;
  }

  public StreamProcessor buildStreamProcessor(
      final SynchronousLogStream stream, final boolean awaitOpening) {
    final var storage = createRuntimeFolder(stream);
    final var snapshot = storage.getParent().resolve(SNAPSHOT_FOLDER);

    final ZeebeDb<?> zeebeDb;
    if (snapshotWasTaken) {
      zeebeDb = zeebeDbFactory.createDb(snapshot.toFile());
    } else {
      zeebeDb = zeebeDbFactory.createDb(storage.toFile());
    }

    final var builder =
        StreamProcessor.builder()
            .logStream(stream.getAsyncLogStream())
            .zeebeDb(zeebeDb)
            .actorSchedulingService(actorScheduler)
            .commandResponseWriter(mockCommandResponseWriter)
            .recordProcessors(recordProcessors)
            .eventApplierFactory(EventAppliers::new) // todo remove this soon
            .streamProcessorMode(streamProcessorMode)
            .listener(mockStreamProcessorListener)
            .partitionCommandSender(mock(InterPartitionCommandSender.class));

    builder.getLifecycleListeners().add(mockProcessorLifecycleAware);

    final StreamProcessor streamProcessor = builder.build();
    final var openFuture = streamProcessor.openAsync(false);

    if (awaitOpening) { // and recovery
      verify(mockProcessorLifecycleAware, timeout(15 * 1000)).onRecovered(any());
    }
    openFuture.join(15, TimeUnit.SECONDS);

    processorContext = new ProcessorContext(streamProcessor, zeebeDb, storage, snapshot);
    closeables.add(processorContext);

    return streamProcessor;
  }

  public void pauseProcessing() {
    processorContext.streamProcessor.pauseProcessing().join();
    LOG.info("Paused processing for processor {}", processorContext.streamProcessor.getName());
  }

  public void resumeProcessing() {
    processorContext.streamProcessor.resumeProcessing();
    LOG.info("Resume processing for processor {}", processorContext.streamProcessor.getName());
  }

  public void snapshot() {
    processorContext.snapshot();
    snapshotWasTaken = true;
    LOG.info("Snapshot database for processor {}", processorContext.streamProcessor.getName());
  }

  public RecordProcessor getDefaultMockedRecordProcessor() {
    return defaultMockedRecordProcessor;
  }

  public StreamProcessor getStreamProcessor() {
    return Optional.ofNullable(processorContext)
        .map(c -> c.streamProcessor)
        .orElseThrow(() -> new NoSuchElementException("No stream processor found."));
  }

  public long writeBatch(final RecordToWrite... recordsToWrite) {
    final var batchWriter = logContext.setupBatchWriter(recordsToWrite);
    return writeBatch(batchWriter);
  }

  public long writeBatch(final LogStreamBatchWriter logStreamBatchWriter) {
    return writeActor.submit(logStreamBatchWriter::tryWrite).join();
  }

  public void closeStreamProcessor() throws Exception {
    processorContext.close();
  }

  public record LogContext(SynchronousLogStream logStream) implements AutoCloseable {

    public LogStreamBatchWriter setupBatchWriter(final RecordToWrite... recordToWrites) {
      final LogStreamBatchWriter logStreamBatchWriter = logStream.newLogStreamBatchWriter();
      for (final RecordToWrite recordToWrite : recordToWrites) {
        logStreamBatchWriter
            .event()
            .key(recordToWrite.getKey())
            .sourceIndex(recordToWrite.getSourceIndex())
            .metadataWriter(recordToWrite.getRecordMetadata())
            .valueWriter(recordToWrite.getUnifiedRecordValue())
            .done();
      }
      return logStreamBatchWriter;
    }

    @Override
    public void close() {
      logStream.close();
    }
  }

  /** Used to run writes within an actor thread. */
  private static final class WriteActor extends Actor {
    public ActorFuture<Long> submit(final Callable<Long> write) {
      return actor.call(write);
    }
  }

  private record ProcessorContext(
      StreamProcessor streamProcessor, ZeebeDb zeebeDb, Path runtimePath, Path snapshotPath)
      implements AutoCloseable {

    public void snapshot() {
      zeebeDb.createSnapshot(snapshotPath.toFile());
    }

    @Override
    public void close() throws Exception {
      if (streamProcessor.isClosed()) {
        return;
      }

      LOG.debug("Close stream processor");
      streamProcessor.closeAsync().join();
      zeebeDb.close();
      if (runtimePath.toFile().exists()) {
        FileUtil.deleteFolder(runtimePath);
      }
    }
  }
}
