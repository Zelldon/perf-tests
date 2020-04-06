package de.zell;/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */

import de.zell.util.TimeAggregation;
import io.zeebe.engine.Loggers;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.test.util.record.RecordingExporter;
import io.zeebe.util.sched.clock.DefaultActorClock;
import io.zeebe.util.sched.testing.ActorSchedulerRule;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public final class PerfTest {

  public static ActorSchedulerRule schedulerRule =
      new ActorSchedulerRule(1, 1, new DefaultActorClock());
  public static EngineRule warmUpRule =
      EngineRule.singlePartition(() -> schedulerRule.get(), 64 * 1024, 256 * 1024);

  @ClassRule
  public static RuleChain ruleChain = RuleChain.outerRule(schedulerRule).around(warmUpRule);

  public static final int WARM_UP_ITERATION = 1_000;
  public static final int ITER_COUNT = 10_000;
  public static final List<TimeAggregation> TIME_AGGREGATIONS = new ArrayList<>();

  private static final String PROCESS_ID = "process";
  private static final BpmnModelInstance WORKFLOW =
      Bpmn.createExecutableProcess(PROCESS_ID).startEvent("startEvent").endEvent().done();

  @Parameter(0)
  public String testName;

  @Rule
  @Parameter(1)
  public EngineRule engineRule;

  private TimeAggregation timeAggregation;

  @Parameters(name = "{0}")
  public static Object[][] parameters() {
    // cfgs
    final int[] entrySizeCfgs = {
      64 * 1024, 128 * 1024, 256 * 1024, 512 * 1024, 1 * 1024 * 1024, 4 * 1024 * 1024
    };

    final int[] segmentSizeCfgs = {
      512 * 1024,
      1 * 1024 * 1024,
      16 * 1024 * 1024,
      64 * 1024 * 1024,
      128 * 1024 * 1024,
      256 * 1024 * 1024,
      512 * 1024 * 1024
    };

    final var tests = new Object[segmentSizeCfgs.length * entrySizeCfgs.length][];
    var testIdx = 0;
    for (int segmentCfgIdx = 0; segmentCfgIdx < segmentSizeCfgs.length; segmentCfgIdx++) {
      final var segmentSize = segmentSizeCfgs[segmentCfgIdx];
      for (int entryCfgIdx = 0; entryCfgIdx < entrySizeCfgs.length; entryCfgIdx++) {
        final var entrySize = entrySizeCfgs[entryCfgIdx];
        final var testName =
            String.format(
                "entrySize %.1f KB - log segment size %.1f MB",
                (entrySize / 1024f), (segmentSize / (1024f * 1024f)));
        tests[testIdx++] =
            new Object[] {
              testName,
              EngineRule.singlePartition(() -> schedulerRule.get(), entrySize, segmentSize)
            };
      }
    }
    return tests;
  }

  @BeforeClass
  public static void warmup() {
    warmUpRule.deployment().withXmlResource(WORKFLOW).deploy();
    Loggers.STREAM_PROCESSING.warn("Will do warm up with {} iterations", WARM_UP_ITERATION);
    final var start = System.nanoTime();
    for (int i = 0; i < WARM_UP_ITERATION; i++) {
      warmUpRule.workflowInstance().ofBpmnProcessId("process").create();
    }
    final var end = System.nanoTime();
    Loggers.STREAM_PROCESSING.warn("Warm up done, took {}", end - start);
  }

  @AfterClass
  public static void printResults() {
    final var testCount = TIME_AGGREGATIONS.size();
    final var parameters = parameters();
    Loggers.WORKFLOW_PROCESSOR_LOGGER.info("Run {} tests. Print results...", testCount);

    final var stringBuilder = new StringBuilder();
    for (int i = 0; i < testCount; i++) {
      final var testName = parameters[i][0];
      final var timeAggregation = TIME_AGGREGATIONS.get(i);
      stringBuilder.append(String.format("%s;%s", testName, timeAggregation.asCSV()));
    }

    Loggers.WORKFLOW_PROCESSOR_LOGGER.info("{}", stringBuilder.toString());
  }

  @Before
  public void setup() {
    Loggers.WORKFLOW_PROCESSOR_LOGGER.warn("Running test {}", testName);
    engineRule.deployment().withXmlResource(WORKFLOW).deploy();
    timeAggregation =
        new TimeAggregation("START_EVENT:ELEMENT_ACTIVATING", "START_EVENT:ELEMENT_ACTIVATED");
  }

  @After
  public void tearDown() {
    TIME_AGGREGATIONS.add(timeAggregation);
  }

  @Test
  public void shouldCreateDeploymentWithBpmnXml() {
    for (int i = 0; i < ITER_COUNT; i++) {
      final var process = engineRule.workflowInstance().ofBpmnProcessId("process").create();

      timeAggregation.add(getStartTime(process), getEndtime(process));
      // TODO timeAggregation.add(getStartTime(process), getEndtime(process));

      if ((i + 1) % 50 == 0) {
        Loggers.STREAM_PROCESSING.warn(timeAggregation.toString());
      }

      // to not collect all records we wrote
      RecordingExporter.reset();
    }

    Loggers.STREAM_PROCESSING.warn(timeAggregation.toString());
  }

  private long getStartTime(final long process) {
    return RecordingExporter.workflowInstanceRecords()
        .withWorkflowInstanceKey(process)
        .withIntent(WorkflowInstanceIntent.ELEMENT_ACTIVATING)
        .withElementId("startEvent")
        .findFirst()
        .orElseThrow()
        .getTimestamp();
  }

  private long getEndtime(final long process) {
    return RecordingExporter.workflowInstanceRecords()
        .withWorkflowInstanceKey(process)
        .withIntent(WorkflowInstanceIntent.ELEMENT_ACTIVATED)
        .withElementId("startEvent")
        .findFirst()
        .orElseThrow()
        .getTimestamp();
  }
}
