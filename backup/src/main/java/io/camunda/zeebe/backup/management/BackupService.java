/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.backup.management;

import io.camunda.zeebe.backup.api.BackupManager;
import io.camunda.zeebe.backup.api.BackupStatus;
import io.camunda.zeebe.backup.api.BackupStatusCode;
import io.camunda.zeebe.backup.api.BackupStore;
import io.camunda.zeebe.backup.common.BackupIdentifierImpl;
import io.camunda.zeebe.backup.common.BackupStatusImpl;
import io.camunda.zeebe.backup.metrics.BackupManagerMetrics;
import io.camunda.zeebe.scheduler.Actor;
import io.camunda.zeebe.scheduler.future.ActorFuture;
import io.camunda.zeebe.scheduler.future.CompletableActorFuture;
import io.camunda.zeebe.snapshots.PersistedSnapshotStore;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Backup manager that takes and manages backup asynchronously */
public final class BackupService extends Actor implements BackupManager {
  private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);
  private final String actorName;
  private final int nodeId;
  private final int partitionId;
  private final int numberOfPartitions;
  private final BackupServiceImpl internalBackupManager;
  private final PersistedSnapshotStore snapshotStore;
  private final Path segmentsDirectory;
  private final Predicate<Path> isSegmentsFile;
  private final BackupManagerMetrics metrics;

  public BackupService(
      final int nodeId,
      final int partitionId,
      final int numberOfPartitions,
      final BackupStore backupStore,
      final PersistedSnapshotStore snapshotStore,
      final Path segmentsDirectory,
      final Predicate<Path> isSegmentsFile) {
    this.nodeId = nodeId;
    this.partitionId = partitionId;
    this.numberOfPartitions = numberOfPartitions;
    this.snapshotStore = snapshotStore;
    this.segmentsDirectory = segmentsDirectory;
    this.isSegmentsFile = isSegmentsFile;
    metrics = new BackupManagerMetrics(partitionId);
    internalBackupManager = new BackupServiceImpl(backupStore);
    actorName = buildActorName(nodeId, "BackupService", partitionId);
  }

  @Override
  public String getName() {
    return actorName;
  }

  @Override
  protected void onActorClosing() {
    internalBackupManager.close();
    metrics.cancelInProgressOperations();
  }

  @Override
  public void takeBackup(final long checkpointId, final long checkpointPosition) {
    actor.run(
        () -> {
          final InProgressBackupImpl inProgressBackup =
              new InProgressBackupImpl(
                  snapshotStore,
                  getBackupId(checkpointId),
                  checkpointPosition,
                  numberOfPartitions,
                  actor,
                  segmentsDirectory,
                  isSegmentsFile);

          final var opMetrics = metrics.startTakingBackup();
          final var backupResult = internalBackupManager.takeBackup(inProgressBackup, actor);
          backupResult.onComplete(opMetrics::complete);

          backupResult.onComplete(
              (ignore, error) -> {
                if (error != null) {
                  LOG.warn(
                      "Failed to take backup {} at position {}",
                      inProgressBackup.checkpointId(),
                      inProgressBackup.checkpointPosition(),
                      error);
                } else {
                  LOG.info(
                      "Backup {} at position {} completed",
                      inProgressBackup.checkpointId(),
                      inProgressBackup.checkpointPosition());
                }
              });
        });
  }

  @Override
  public ActorFuture<BackupStatus> getBackupStatus(final long checkpointId) {
    final var operationMetrics = metrics.startQueryingStatus();

    final var future = new CompletableActorFuture<BackupStatus>();
    internalBackupManager
        .getBackupStatus(partitionId, checkpointId, actor)
        .onComplete(
            (backupStatus, throwable) -> {
              if (throwable != null) {
                future.completeExceptionally(throwable);
              } else {
                if (backupStatus.isEmpty()) {
                  future.complete(
                      new BackupStatusImpl(
                          getBackupId(checkpointId),
                          Optional.empty(),
                          BackupStatusCode.DOES_NOT_EXIST,
                          Optional.empty(),
                          Optional.empty(),
                          Optional.empty()));
                } else {
                  future.complete(backupStatus.get());
                }
              }
            });
    future.onComplete(operationMetrics::complete);
    return future;
  }

  @Override
  public ActorFuture<Void> deleteBackup(final long checkpointId) {
    final var operationMetrics = metrics.startDeleting();

    final CompletableActorFuture<Void> backupDeleted =
        CompletableActorFuture.completedExceptionally(
            new UnsupportedOperationException("Not implemented"));

    backupDeleted.onComplete(operationMetrics::complete);

    return backupDeleted;
  }

  @Override
  public void failInProgressBackup(final long lastCheckpointId) {
    internalBackupManager.failInProgressBackups(partitionId, lastCheckpointId, actor);
  }

  private BackupIdentifierImpl getBackupId(final long checkpointId) {
    return new BackupIdentifierImpl(nodeId, partitionId, checkpointId);
  }
}
