/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.state.appliers;

import io.camunda.zeebe.engine.state.TypedEventApplier;
import io.camunda.zeebe.engine.state.instance.ElementInstance;
import io.camunda.zeebe.engine.state.mutable.MutableElementInstanceState;
import io.camunda.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceModificationRecord;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceModificationIntent;

final class ProcessInstanceModifiedEventApplier
    implements TypedEventApplier<
        ProcessInstanceModificationIntent, ProcessInstanceModificationRecord> {

  private final MutableElementInstanceState elementInstanceState;

  public ProcessInstanceModifiedEventApplier(
      final MutableElementInstanceState elementInstanceState) {
    this.elementInstanceState = elementInstanceState;
  }

  @Override
  public void applyState(final long key, final ProcessInstanceModificationRecord value) {
    value.getActivatedElementInstanceKeys().stream()
        .map(elementInstanceState::getInstance)
        .filter(ElementInstance::isInterrupted)
        .forEach(
            instance ->
                elementInstanceState.updateInstance(
                    instance.getKey(), ElementInstance::clearInterruptedState));
  }
}
