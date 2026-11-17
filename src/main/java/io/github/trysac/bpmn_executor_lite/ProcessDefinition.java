package io.github.trysac.bpmn_executor_lite;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable parsed representation of a BPMN 2.0 process. */
public final class ProcessDefinition {

  private final String id;
  private final Map<String, FlowNode> nodes;
  private final Map<String, SequenceFlow> sequenceFlows;
  private final List<FlowNode> startEvents;
  private final Map<String, List<SequenceFlow>> outgoingBySource;
  private final Map<String, List<SequenceFlow>> incomingByTarget;
  private final Map<String, List<FlowNode>> boundaryEventsByAttachedRef;

  ProcessDefinition(
      String id,
      Map<String, FlowNode> nodes,
      Map<String, SequenceFlow> sequenceFlows,
      List<FlowNode> startEvents,
      Map<String, List<SequenceFlow>> outgoingBySource,
      Map<String, List<SequenceFlow>> incomingByTarget,
      Map<String, List<FlowNode>> boundaryEventsByAttachedRef) {
    this.id = Objects.requireNonNull(id, "id");
    this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    this.sequenceFlows = Collections.unmodifiableMap(new LinkedHashMap<>(sequenceFlows));
    this.startEvents = List.copyOf(startEvents);
    this.outgoingBySource = copyMultiMap(outgoingBySource);
    this.incomingByTarget = copyMultiMap(incomingByTarget);
    this.boundaryEventsByAttachedRef = copyMultiMap(boundaryEventsByAttachedRef);
  }

  /**
   * Returns the process identifier.
   *
   * @return process id
   */
  public String id() {
    return id;
  }

  Map<String, FlowNode> nodes() {
    return nodes;
  }

  Map<String, SequenceFlow> sequenceFlows() {
    return sequenceFlows;
  }

  List<FlowNode> startEvents() {
    return startEvents;
  }

  List<SequenceFlow> outgoing(String nodeId) {
    return outgoingBySource.getOrDefault(nodeId, List.of());
  }

  List<SequenceFlow> incoming(String nodeId) {
    return incomingByTarget.getOrDefault(nodeId, List.of());
  }

  List<FlowNode> boundaryEventsFor(String activityId) {
    return boundaryEventsByAttachedRef.getOrDefault(activityId, List.of());
  }

  private static <V> Map<String, List<V>> copyMultiMap(Map<String, List<V>> map) {
    Map<String, List<V>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, List<V>> e : map.entrySet()) {
      copy.put(e.getKey(), List.copyOf(e.getValue()));
    }
    return Collections.unmodifiableMap(copy);
  }

  enum NodeKind {
    START_EVENT,
    END_EVENT,
    USER_TASK,
    SERVICE_TASK,
    EXCLUSIVE_GATEWAY,
    PARALLEL_GATEWAY,
    INTERMEDIATE_CATCH_EVENT,
    BOUNDARY_EVENT
  }

  record TimerSpec(Duration duration, Instant dateTime) {
    Instant dueAt(Instant base) {
      if (duration != null) {
        return base.plus(duration);
      }
      return dateTime;
    }
  }

  record FlowNode(
      String id,
      NodeKind kind,
      String defaultFlowId,
      boolean terminateEnd,
      TimerSpec timer,
      String attachedToRef,
      boolean cancelActivity) {

    String auditKind() {
      return switch (kind) {
        case START_EVENT -> "startEvent";
        case END_EVENT -> terminateEnd ? "terminateEndEvent" : "endEvent";
        case USER_TASK -> "userTask";
        case SERVICE_TASK -> "serviceTask";
        case EXCLUSIVE_GATEWAY -> "exclusiveGateway";
        case PARALLEL_GATEWAY -> "parallelGateway";
        case INTERMEDIATE_CATCH_EVENT -> "intermediateCatchEvent";
        case BOUNDARY_EVENT -> "boundaryEvent";
      };
    }
  }

  record SequenceFlow(
      String id,
      String sourceRef,
      String targetRef,
      String conditionText,
      ConditionEvaluator.Expr compiledCondition) {}
}
