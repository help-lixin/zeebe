/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.camunda.zeebe.model.bpmn.util;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import io.camunda.zeebe.model.bpmn.instance.Activity;
import io.camunda.zeebe.model.bpmn.instance.BoundaryEvent;
import io.camunda.zeebe.model.bpmn.instance.Error;
import io.camunda.zeebe.model.bpmn.instance.ErrorEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.EventDefinition;
import io.camunda.zeebe.model.bpmn.instance.IntermediateCatchEvent;
import io.camunda.zeebe.model.bpmn.instance.IntermediateThrowEvent;
import io.camunda.zeebe.model.bpmn.instance.LinkEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.Message;
import io.camunda.zeebe.model.bpmn.instance.MessageEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.Signal;
import io.camunda.zeebe.model.bpmn.instance.SignalEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.StartEvent;
import io.camunda.zeebe.model.bpmn.instance.SubProcess;
import io.camunda.zeebe.model.bpmn.instance.TimerEventDefinition;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

public class ModelUtil {

  private static final List<Class<? extends EventDefinition>> NON_INTERRUPTING_EVENT_DEFINITIONS =
      Arrays.asList(
          MessageEventDefinition.class, TimerEventDefinition.class, SignalEventDefinition.class);

  public static List<EventDefinition> getEventDefinitionsForBoundaryEvents(final Activity element) {
    return element.getBoundaryEvents().stream()
        .flatMap(event -> event.getEventDefinitions().stream())
        .collect(Collectors.toList());
  }

  public static List<EventDefinition> getEventDefinitionsForEventSubprocesses(
      final ModelElementInstance element) {
    return element.getChildElementsByType(SubProcess.class).stream()
        .filter(SubProcess::triggeredByEvent)
        .flatMap(subProcess -> subProcess.getChildElementsByType(StartEvent.class).stream())
        .flatMap(s -> s.getEventDefinitions().stream())
        .collect(Collectors.toList());
  }

  public static List<EventDefinition> getEventDefinitionsForSignalStartEvents(
      final ModelElementInstance element) {
    return element.getChildElementsByType(StartEvent.class).stream()
        .flatMap(i -> i.getEventDefinitions().stream())
        .filter(e -> e instanceof SignalEventDefinition)
        .collect(Collectors.toList());
  }

  public static List<EventDefinition> getEventDefinitionsForLinkCatchEvents(
      final ModelElementInstance element) {
    return element.getChildElementsByType(IntermediateCatchEvent.class).stream()
        .flatMap(i -> i.getEventDefinitions().stream())
        .filter(e -> e instanceof LinkEventDefinition)
        .collect(Collectors.toList());
  }

  public static List<EventDefinition> getEventDefinitionsForLinkThrowEvents(
      final ModelElementInstance element) {
    return element.getChildElementsByType(IntermediateThrowEvent.class).stream()
        .flatMap(i -> i.getEventDefinitions().stream())
        .filter(e -> e instanceof LinkEventDefinition)
        .collect(Collectors.toList());
  }

  public static List<String> getDuplicateMessageNames(
      final Stream<MessageEventDefinition> eventDefinitions) {

    final Stream<Message> messages =
        eventDefinitions
            .map(MessageEventDefinition::getMessage)
            .filter(m -> m.getName() != null && !m.getName().isEmpty());

    return messages.collect(groupingBy(Message::getName, counting())).entrySet().stream()
        .filter(e -> e.getValue() > 1)
        .map(Entry::getKey)
        .collect(Collectors.toList());
  }

  public static void verifyNoDuplicatedBoundaryEvents(
      final Activity activity, final Consumer<String> errorCollector) {

    final List<EventDefinition> definitions = getEventDefinitionsForBoundaryEvents(activity);

    verifyNoDuplicatedEventDefinition(definitions, errorCollector);
  }

  public static void verifyNoDuplicateSignalStartEvents(
      final ModelElementInstance element, final Consumer<String> errorCollector) {

    final List<EventDefinition> definitions = getEventDefinitionsForSignalStartEvents(element);

    verifyNoDuplicatedEventDefinition(definitions, errorCollector);
  }

  public static void verifyLinkIntermediateEvents(
      final ModelElementInstance element, final Consumer<String> errorCollector) {

    // get link catch events definition
    final List<EventDefinition> linkCatchEvents = getEventDefinitionsForLinkCatchEvents(element);

    verifyNoDuplicatedEventDefinition(linkCatchEvents, errorCollector);

    // get link throw events definition
    final List<EventDefinition> linkThrowEvents = getEventDefinitionsForLinkThrowEvents(element);

    // get link catch events names
    final Set<String> linkCatchList =
        getEventDefinition(linkCatchEvents, LinkEventDefinition.class)
            .filter(def -> def.getName() != null && !def.getName().isEmpty())
            .map(LinkEventDefinition::getName)
            .collect(Collectors.toSet());

    // get link throw events names
    final Set<String> linkThrowList =
        getEventDefinition(linkThrowEvents, LinkEventDefinition.class)
            .filter(def -> def.getName() != null && !def.getName().isEmpty())
            .map(LinkEventDefinition::getName)
            .collect(Collectors.toSet());

    linkThrowList.forEach(
        item -> {
          if (!linkCatchList.contains(item)) {
            errorCollector.accept(noPairedLinkNames(item));
          }
        });
  }

  public static void verifyEventDefinition(
      final BoundaryEvent boundaryEvent, final Consumer<String> errorCollector) {
    boundaryEvent
        .getEventDefinitions()
        .forEach(
            definition ->
                verifyEventDefinition(definition, boundaryEvent.cancelActivity(), errorCollector));
  }

  public static void verifyEventDefinition(
      final StartEvent startEvent, final Consumer<String> errorCollector) {

    startEvent
        .getEventDefinitions()
        .forEach(
            definition ->
                verifyEventDefinition(definition, startEvent.isInterrupting(), errorCollector));
  }

  public static void verifyNoDuplicatedEventSubprocesses(
      final ModelElementInstance element, final Consumer<String> errorCollector) {

    final List<EventDefinition> definitions = getEventDefinitionsForEventSubprocesses(element);

    verifyNoDuplicatedEventDefinition(definitions, errorCollector);
  }

  public static void verifyNoDuplicatedEventDefinition(
      final Collection<? extends EventDefinition> definitions,
      final Consumer<String> errorCollector) {

    final Stream<String> messageNames =
        getEventDefinition(definitions, MessageEventDefinition.class)
            .filter(def -> def.getMessage() != null)
            .map(MessageEventDefinition::getMessage)
            .filter(message -> message.getName() != null && !message.getName().isEmpty())
            .map(Message::getName);

    getDuplicatedEntries(messageNames)
        .map(ModelUtil::duplicatedMessageNames)
        .forEach(errorCollector);

    final Stream<String> errorCodes =
        getEventDefinition(definitions, ErrorEventDefinition.class)
            .filter(def -> def.getError() != null)
            .map(ErrorEventDefinition::getError)
            .filter(error -> error.getErrorCode() != null && !error.getErrorCode().isEmpty())
            .map(Error::getErrorCode);

    getDuplicatedEntries(errorCodes).map(ModelUtil::duplicatedErrorCodes).forEach(errorCollector);

    final Stream<String> signalNames =
        getEventDefinition(definitions, SignalEventDefinition.class)
            .filter(def -> def.getSignal() != null)
            .map(SignalEventDefinition::getSignal)
            .filter(signal -> signal.getName() != null && !signal.getName().isEmpty())
            .map(Signal::getName);

    getDuplicatedEntries(signalNames).map(ModelUtil::duplicatedSignalNames).forEach(errorCollector);

    final Stream<String> linkNames =
        getEventDefinition(definitions, LinkEventDefinition.class)
            .filter(def -> def.getName() != null && !def.getName().isEmpty())
            .map(LinkEventDefinition::getName);

    getDuplicatedEntries(linkNames).map(ModelUtil::duplicatedLinkNames).forEach(errorCollector);
  }

  public static <T extends EventDefinition> Stream<T> getEventDefinition(
      final Collection<? extends EventDefinition> collection, final Class<T> type) {
    return collection.stream().filter(type::isInstance).map(type::cast);
  }

  public static Stream<String> getDuplicatedEntries(final Stream<String> stream) {
    return stream.collect(groupingBy(Function.identity(), counting())).entrySet().stream()
        .filter(e -> e.getValue() > 1)
        .map(Entry::getKey);
  }

  private static String duplicatedMessageNames(final String messageName) {
    return String.format(
        "Multiple message event definitions with the same name '%s' are not allowed.", messageName);
  }

  private static String duplicatedErrorCodes(final String errorCode) {
    return String.format(
        "Multiple error event definitions with the same errorCode '%s' are not allowed.",
        errorCode);
  }

  private static String duplicatedSignalNames(final String signalName) {
    return String.format(
        "Multiple signal event definitions with the same name '%s' are not allowed.", signalName);
  }

  private static String duplicatedLinkNames(final String linkName) {
    return String.format(
        "Multiple intermediate catch link event definitions with the same name '%s' are not allowed.",
        linkName);
  }

  private static String noPairedLinkNames(final String linkName) {
    return String.format(
        "Can't find an catch link event for the throw link event with the name '%s'.", linkName);
  }

  private static void verifyEventDefinition(
      final EventDefinition definition,
      final boolean isInterrupting,
      final Consumer<String> errorCollector) {

    if (!isInterrupting
        && !NON_INTERRUPTING_EVENT_DEFINITIONS.contains(
            definition.getElementType().getInstanceType())) {
      errorCollector.accept("Non-Interrupting event of this type is not allowed");
    }

    if (isInterrupting && definition instanceof TimerEventDefinition) {
      final TimerEventDefinition timerEventDefinition = (TimerEventDefinition) definition;
      if (timerEventDefinition.getTimeCycle() != null) {
        errorCollector.accept("Interrupting timer event with time cycle is not allowed.");
      }
    }
  }
}
