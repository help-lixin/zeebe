/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.streamprocessor;

import io.camunda.zeebe.engine.processing.streamprocessor.StreamProcessor.ProcessingSchedulingService;

public interface StreamProcessorLifecycleAware {

  /** Callback after reprocessing was successful and before regular processing begins */
  default void onRecovered(final ProcessingSchedulingService schedulingService) {}

  /** Callback which is called when StreamProcessor is on closing phase. */
  default void onClose() {}

  /** Callback which is called when the StreamProcessor failed, during startup or processing. */
  default void onFailed() {}

  /**
   * Callback which is called when the processing is paused, will only called after onRecovered was
   * called before.
   */
  default void onPaused() {}

  /**
   * Callback which is called when the processing is resumed, will only called after onPaused was
   * called before.
   */
  default void onResumed() {}
}
