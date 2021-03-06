/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package de.zell.client;

import de.zell.StreamProcessorRule;
import de.zell.StreamProcessorRule;
import io.zeebe.protocol.Protocol;
import io.zeebe.protocol.impl.record.value.incident.IncidentRecord;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.intent.IncidentIntent;
import io.zeebe.protocol.record.value.IncidentRecordValue;
import io.zeebe.test.util.record.RecordingExporter;

public final class IncidentClient {

  private final StreamProcessorRule environmentRule;

  public IncidentClient(final StreamProcessorRule environmentRule) {
    this.environmentRule = environmentRule;
  }

  public ResolveIncidentClient ofInstance(final long workflowInstanceKey) {
    return new ResolveIncidentClient(environmentRule, workflowInstanceKey);
  }

  public static class ResolveIncidentClient {
    private static final long DEFAULT_KEY = -1L;

    private final StreamProcessorRule environmentRule;
    private final long workflowInstanceKey;
    private final IncidentRecord incidentRecord;

    private long incidentKey = DEFAULT_KEY;

    public ResolveIncidentClient(
        final StreamProcessorRule environmentRule, final long workflowInstanceKey) {
      this.environmentRule = environmentRule;
      this.workflowInstanceKey = workflowInstanceKey;
      incidentRecord = new IncidentRecord();
    }

    public ResolveIncidentClient withKey(final long incidentKey) {
      this.incidentKey = incidentKey;
      return this;
    }

    public Record<IncidentRecordValue> resolve() {
      if (incidentKey == DEFAULT_KEY) {
        incidentKey =
            RecordingExporter.incidentRecords(IncidentIntent.CREATED)
                .withWorkflowInstanceKey(workflowInstanceKey)
                .getFirst()
                .getKey();
      }

      final long position =
          environmentRule.writeCommandOnPartition(
              Protocol.decodePartitionId(incidentKey),
              incidentKey,
              IncidentIntent.RESOLVE,
              incidentRecord);

      return RecordingExporter.incidentRecords()
          .withWorkflowInstanceKey(workflowInstanceKey)
          .withRecordKey(incidentKey)
          .withSourceRecordPosition(position)
          .withIntent(IncidentIntent.RESOLVED)
          .getFirst();
    }
  }
}
