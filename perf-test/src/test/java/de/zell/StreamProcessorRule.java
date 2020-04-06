package de.zell;/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */

import static de.zell.StreamProcessingComposite.getLogName;

import io.zeebe.db.ZeebeDbFactory;
import io.zeebe.engine.processor.CommandResponseWriter;
import io.zeebe.engine.processor.StreamProcessor;
import io.zeebe.engine.processor.TypedRecord;
import io.zeebe.engine.processor.TypedRecordProcessorFactory;
import de.zell.StreamProcessingComposite.StreamProcessorTestFactory;
import io.zeebe.engine.state.ZeebeState;
import de.zell.util.RecordStream;
import io.zeebe.logstreams.log.LogStreamRecordWriter;
import io.zeebe.logstreams.state.StateSnapshotController;
import io.zeebe.logstreams.util.SynchronousLogStream;
import io.zeebe.msgpack.UnpackedObject;
import io.zeebe.protocol.record.intent.Intent;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.test.util.AutoCloseableRule;
import io.zeebe.util.FileUtil;
import io.zeebe.util.ZbLogger;
import io.zeebe.util.allocation.DirectBufferAllocator;
import io.zeebe.util.sched.ActorScheduler;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;

public final class StreamProcessorRule implements TestRule {

  private static final Logger LOG = new ZbLogger("io.zeebe.broker.test");

  private static final int PARTITION_ID = 0;

  // environment
  private final TemporaryFolder temporaryFolder;
  private final AutoCloseableRule closeables = new AutoCloseableRule();
  private final ZeebeDbFactory zeebeDbFactory;
  private final SetupRule rule;
  private final int startPartitionId;
  private final int partitionCount;
  private final RuleChain chain;
  private final Supplier<ActorScheduler> actorSchedulerSupplier;
  private TestStreams streams;
  private StreamProcessingComposite streamProcessingComposite;

  private final int maxEntrySize;
  private final int maxSegmentSize;
  private final File dir;

  public StreamProcessorRule(
      final Supplier<ActorScheduler> actorSchedulerSupplier,
      final int startPartitionId,
      final int partitionCount,
      final ZeebeDbFactory dbFactory,
      final TemporaryFolder temporaryFolder,
      final int maxEntrySize,
      final int maxSegmentSize) {
    this.startPartitionId = startPartitionId;
    this.partitionCount = partitionCount;
    this.actorSchedulerSupplier = actorSchedulerSupplier;
    rule = new SetupRule(startPartitionId, partitionCount);

    dir =
        new File(
            FileChannelTest.class.getResource(".").getPath(),
            "log-" + ThreadLocalRandom.current().nextInt());
    dir.mkdirs();
    zeebeDbFactory = dbFactory;
    this.temporaryFolder = temporaryFolder;
    chain =
        RuleChain.outerRule(temporaryFolder)
            .around(new CleanUpRule(() -> dir))
            .around(closeables)
            .around(rule)
            .around(new FailedTestRecordPrinter());

    this.maxEntrySize = maxEntrySize;
    this.maxSegmentSize = maxSegmentSize;
  }

  @Override
  public Statement apply(final Statement base, final Description description) {
    return chain.apply(base, description);
  }

  public LogStreamRecordWriter getLogStreamRecordWriter(final int partitionId) {
    return streamProcessingComposite.getLogStreamRecordWriter(partitionId);
  }

  public StreamProcessor startTypedStreamProcessor(final StreamProcessorTestFactory factory) {
    return streamProcessingComposite.startTypedStreamProcessor(factory);
  }

  public StreamProcessor startTypedStreamProcessor(final TypedRecordProcessorFactory factory) {
    return startTypedStreamProcessor(startPartitionId, factory);
  }

  public StreamProcessor startTypedStreamProcessor(
      final int partitionId, final TypedRecordProcessorFactory factory) {
    return streamProcessingComposite.startTypedStreamProcessor(partitionId, factory);
  }

  public void closeStreamProcessor(final int partitionId) {
    streamProcessingComposite.closeStreamProcessor(partitionId);
  }

  public void closeStreamProcessor() {
    closeStreamProcessor(startPartitionId);
  }

  public StateSnapshotController getStateSnapshotController(final int partitionId) {
    return streams.getStateSnapshotController(getLogName(partitionId));
  }

  public StateSnapshotController getStateSnapshotController() {
    return getStateSnapshotController(startPartitionId);
  }

  public CommandResponseWriter getCommandResponseWriter() {
    return streams.getMockedResponseWriter();
  }

  public Consumer<TypedRecord> getProcessedListener() {
    return streams.getMockedOnProcessedListener();
  }

  public ZeebeState getZeebeState() {
    return streamProcessingComposite.getZeebeState();
  }

  public RecordStream events() {
    return new RecordStream(streams.events(getLogName(startPartitionId)));
  }

  public long writeWorkflowInstanceEvent(final WorkflowInstanceIntent intent) {
    return writeWorkflowInstanceEvent(intent, 1);
  }

  public long writeWorkflowInstanceEventWithSource(
      final WorkflowInstanceIntent intent, final int instanceKey, final long sourceEventPosition) {
    return streamProcessingComposite.writeWorkflowInstanceEventWithSource(
        intent, instanceKey, sourceEventPosition);
  }

  public long writeWorkflowInstanceEvent(
      final WorkflowInstanceIntent intent, final int instanceKey) {
    return streamProcessingComposite.writeWorkflowInstanceEvent(intent, instanceKey);
  }

  public long writeEvent(final long key, final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeEvent(key, intent, value);
  }

  public long writeEvent(final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeEvent(intent, value);
  }

  public long writeBatch(final RecordToWrite... recordToWrites) {
    return streamProcessingComposite.writeBatch(recordToWrites);
  }

  public long writeCommandOnPartition(
      final int partition, final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeCommandOnPartition(partition, intent, value);
  }

  public long writeCommandOnPartition(
      final int partition, final long key, final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeCommandOnPartition(partition, key, intent, value);
  }

  public long writeCommand(final long key, final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeCommand(key, intent, value);
  }

  public long writeCommand(final Intent intent, final UnpackedObject value) {
    return streamProcessingComposite.writeCommand(intent, value);
  }

  public long writeCommand(
      final int requestStreamId,
      final long requestId,
      final Intent intent,
      final UnpackedObject value) {
    return streamProcessingComposite.writeCommand(requestStreamId, requestId, intent, value);
  }

  private class SetupRule extends ExternalResource {

    private final int startPartitionId;
    private final int partitionCount;

    SetupRule(final int startPartitionId, final int partitionCount) {
      this.startPartitionId = startPartitionId;
      this.partitionCount = partitionCount;
    }

    @Override
    protected void before() {
      streams =
          new TestStreams(
              dir,
              temporaryFolder,
              closeables,
              actorSchedulerSupplier.get(),
              maxEntrySize,
              maxSegmentSize);

      int partitionId = startPartitionId;
      for (int i = 0; i < partitionCount; i++) {
        streams.createLogStream(getLogName(partitionId), partitionId++);
      }

      streamProcessingComposite =
          new StreamProcessingComposite(streams, startPartitionId, zeebeDbFactory);
    }

    @Override
    protected void after() {
      streams = null;
    }
  }

  private class FailedTestRecordPrinter extends TestWatcher {

    @Override
    protected void failed(final Throwable e, final Description description) {
      LOG.info("Test failed, following records where exported:");
    }
  }

  private static class CleanUpRule extends ExternalResource {

    private File root;
    private final Supplier<File> rootSupplier;

    CleanUpRule(final Supplier<File> rootSupplier) {
      this.rootSupplier = rootSupplier;
    }

    @Override
    protected void before() {
      root = rootSupplier.get();
    }

    @Override
    protected void after() {
      try {
        LOG.debug("Clean up test files on path {}", root);
        FileUtil.deleteFolder(root.toPath());

        final long allocatedMemoryInKb = DirectBufferAllocator.getAllocatedMemoryInKb();
        if (allocatedMemoryInKb > 0) {
          LOG.warn(
              "There are still allocated direct buffers of a total size of {}kB.",
              allocatedMemoryInKb);
        }
      } catch (final IOException e) {
        LOG.error("Error on deleting root test folder", e);
      }
    }
  }
}
