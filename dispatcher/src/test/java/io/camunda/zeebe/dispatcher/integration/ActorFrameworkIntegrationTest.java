/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.dispatcher.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.dispatcher.BlockPeek;
import io.camunda.zeebe.dispatcher.ClaimedFragment;
import io.camunda.zeebe.dispatcher.Dispatcher;
import io.camunda.zeebe.dispatcher.Dispatchers;
import io.camunda.zeebe.dispatcher.FragmentHandler;
import io.camunda.zeebe.dispatcher.Subscription;
import io.camunda.zeebe.scheduler.Actor;
import io.camunda.zeebe.scheduler.future.ActorFuture;
import io.camunda.zeebe.scheduler.testing.ActorSchedulerRule;
import io.camunda.zeebe.util.ByteValue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.agrona.DirectBuffer;
import org.junit.Rule;
import org.junit.Test;

/**
 * NOTE: make sure that all actors which will close over the dispatcher buffers are closed before
 * closing the dispatcher, as that will free the underlying buffer.
 */
public final class ActorFrameworkIntegrationTest {
  @Rule public final ActorSchedulerRule actorSchedulerRule = new ActorSchedulerRule(3);

  @Test
  public void testClaim() throws InterruptedException {
    final Dispatcher dispatcher =
        Dispatchers.create("default")
            .actorSchedulingService(actorSchedulerRule.get())
            .bufferSize((int) ByteValue.ofMegabytes(10))
            .build();
    final Consumer consumer = new Consumer(dispatcher);
    final ClaimingProducer producer = new ClaimingProducer(dispatcher);

    actorSchedulerRule.submitActor(consumer);
    actorSchedulerRule.submitActor(producer);

    assertThat(producer.latch.await(10, TimeUnit.SECONDS)).isTrue();
    producer.close();
    consumer.close();
    dispatcher.close();
  }

  @Test
  public void testClaimAndPeek() throws InterruptedException {
    final Dispatcher dispatcher =
        Dispatchers.create("default")
            .actorSchedulingService(actorSchedulerRule.get())
            .bufferSize((int) ByteValue.ofMegabytes(10))
            .build();
    final PeekingConsumer consumer = new PeekingConsumer(dispatcher);
    final ClaimingProducer producer = new ClaimingProducer(dispatcher);

    actorSchedulerRule.submitActor(consumer);
    actorSchedulerRule.submitActor(producer);

    assertThat(producer.latch.await(10, TimeUnit.SECONDS)).isTrue();
    producer.close();
    consumer.close();
    dispatcher.close();
  }

  static final class ClaimingProducer extends Actor {
    final CountDownLatch latch = new CountDownLatch(1);

    final int totalWork = 10_000;

    final Dispatcher dispatcher;
    final ClaimedFragment claim = new ClaimedFragment();
    int counter = 1;

    ClaimingProducer(final Dispatcher dispatcher) {
      this.dispatcher = dispatcher;
    }

    @Override
    protected void onActorStarted() {
      actor.run(this::produce);
    }

    void produce() {
      if (dispatcher.claimSingleFragment(claim, 4534) >= 0) {
        claim.getBuffer().putInt(claim.getOffset(), counter++);
        claim.commit();
      }

      if (counter < totalWork) {
        actor.yieldThread();
        actor.run(this::produce);
      } else {
        latch.countDown();
      }
    }
  }

  static final class Consumer extends Actor implements FragmentHandler {
    final Dispatcher dispatcher;
    Subscription subscription;
    int counter = 0;

    Consumer(final Dispatcher dispatcher) {
      this.dispatcher = dispatcher;
    }

    @Override
    protected void onActorStarted() {
      final ActorFuture<Subscription> future =
          dispatcher.openSubscriptionAsync("consumerSubscription-" + hashCode());
      actor.runOnCompletion(
          future,
          (s, t) -> {
            subscription = s;
            actor.consume(subscription, this::consume);
          });
    }

    void consume() {
      subscription.poll(this, Integer.MAX_VALUE);
    }

    @Override
    public int onFragment(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final int streamId,
        final boolean isMarkedFailed) {
      final int newCounter = buffer.getInt(offset);
      if (newCounter - 1 != counter) {
        throw new RuntimeException(newCounter + " " + counter);
      }
      counter = newCounter;
      return FragmentHandler.CONSUME_FRAGMENT_RESULT;
    }
  }

  static final class PeekingConsumer extends Actor implements FragmentHandler {
    final Dispatcher dispatcher;
    final BlockPeek peek = new BlockPeek();
    Subscription subscription;
    int counter = 0;
    final Runnable processPeek =
        () -> {
          try {
            processPeek();
          } catch (final Exception e) {
            actor.submit(this::processPeek);
          }
        };

    PeekingConsumer(final Dispatcher dispatcher) {
      this.dispatcher = dispatcher;
    }

    @Override
    protected void onActorStarted() {
      final ActorFuture<Subscription> future =
          dispatcher.openSubscriptionAsync("consumerSubscription-" + hashCode());
      actor.runOnCompletion(
          future,
          (s, t) -> {
            subscription = s;
            actor.consume(subscription, this::consume);
          });
    }

    void consume() {
      if (subscription.peekBlock(peek, Integer.MAX_VALUE, true) > 0) {
        actor.submit(processPeek);
      }
    }

    void processPeek() {
      for (final DirectBuffer directBuffer : peek) {
        final int newCounter = directBuffer.getInt(0);
        if (newCounter - 1 != counter) {
          throw new RuntimeException(newCounter + " " + counter);
        }
        counter = newCounter;
      }
      peek.markCompleted();
    }

    @Override
    public int onFragment(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final int streamId,
        final boolean isMarkedFailed) {
      final int newCounter = buffer.getInt(offset);
      if (newCounter - 1 != counter) {
        throw new RuntimeException(newCounter + " " + counter);
      }
      counter = newCounter;
      return FragmentHandler.CONSUME_FRAGMENT_RESULT;
    }
  }
}
