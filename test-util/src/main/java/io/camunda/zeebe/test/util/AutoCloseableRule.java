/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.test.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.agrona.CloseHelper;
import org.junit.After;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saves you some {@link After} methods by closing {@link AutoCloseable} implementations after the
 * test in LIFO fashion.
 *
 * @author Lindhauer
 */
public final class AutoCloseableRule extends ExternalResource {
  private static final Logger LOGGER = LoggerFactory.getLogger(AutoCloseableRule.class);

  final List<AutoCloseable> thingsToClose = new ArrayList<>();

  public void manage(final AutoCloseable closeable) {
    thingsToClose.add(closeable);
  }

  @Override
  public void after() {
    Collections.reverse(thingsToClose);
    CloseHelper.closeAll(
        error -> LOGGER.error("Failed to close managed AutoCloseable", error), thingsToClose);
  }
}
