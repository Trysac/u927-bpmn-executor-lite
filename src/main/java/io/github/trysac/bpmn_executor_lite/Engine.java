package io.github.trysac.bpmn_executor_lite;

import io.github.trysac.bpmn_executor_lite.Instance.MutableState;
import io.github.trysac.bpmn_executor_lite.ProcessDefinition.FlowNode;
import io.github.trysac.bpmn_executor_lite.ProcessDefinition.NodeKind;
import io.github.trysac.bpmn_executor_lite.ProcessDefinition.SequenceFlow;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Token-based execution engine for parsed {@link ProcessDefinition} models.
 *
 * <p>All time-dependent behavior uses the injected {@link Clock}, service tasks invoke registered
 * {@link ServiceHandler} callbacks, and every activity execution emits an {@link AuditEvent}.
 */
public final class Engine {

  /**
   * Maximum number of flow node transition steps per {@code start}, {@code complete}, or {@code
   * tick} call.
   */
  public static final int STEP_LIMIT = 10_000;

  /**
   * Maximum number of tracked active instances retained in memory before finished instances are
   * evicted.
   */
  public static final int MAX_TRACKED_INSTANCES = 10_000;

  private final Clock clock;
  private final Map<String, ServiceHandler> handlers;
  private final Consumer<AuditEvent> onEvent;
  private final Map<String, MutableState> trackedInstances = new LinkedHashMap<>();
  private long instanceCounter = 0L;

  /**
   * Creates a new BPMN execution engine.
   *
   * @param clock clock used for audit timestamps and timer evaluation
   * @param handlers map of serviceTask element id to {@link ServiceHandler}
   * @param onEvent optional callback invoked whenever an {@link AuditEvent} is recorded (may be
   *     null)
   */
  public Engine(Clock clock, Map<String, ServiceHandler> handlers, Consumer<AuditEvent> onEvent) {
    this.clock = Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(handlers, "handlers");
    this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(handlers));
    this.onEvent = onEvent != null ? onEvent : event -> {};
  }

  /**
   * Starts a new process instance of the given definition with initial variables.
   *
   * @param def parsed process definition
   * @param variables initial variable map
   * @return the started {@link Instance}
   */
  public Instance start(ProcessDefinition def, Map<String, Object> variables) {
    Objects.requireNonNull(def, "def");
    Objects.requireNonNull(variables, "variables");

    String instanceId = "inst-" + ++instanceCounter;
    MutableState state = new MutableState(instanceId, def, Instance.STATE_RUNNING);
    state.variables.putAll(variables);
    trackInstance(state);

    FlowNode selectedStart = selectStartEvent(def);
    Deque<Token> queue = new ArrayDeque<>();

    if (selectedStart.timer() != null) {
      Instant now = clock.now();
      Instant due = selectedStart.timer().dueAt(now);
      if (!due.isAfter(now)) {
        queue.addLast(new Token(selectedStart.id(), null));
      } else {
        state.waitingTimers.add(new ActiveTimer(selectedStart.id(), due, null, true));
        syncActiveTasks(state);
        state.state = Instance.STATE_WAITING;
        return Instance.createLive(state);
      }
    } else {
      queue.addLast(new Token(selectedStart.id(), null));
    }

    advance(state, queue, null, false);
    return Instance.createLive(state);
  }

  /**
   * Completes a waiting user task on the given instance, merging new variables and continuing
   * execution.
   *
   * @param instance the process instance
   * @param taskId the BPMN id of the active userTask
   * @param variables variables to merge into the instance
   * @throws BpmnException with code {@code "unknown_task"} if {@code taskId} is not an active user
   *     task
   */
  public void complete(Instance instance, String taskId, Map<String, Object> variables) {
    Objects.requireNonNull(instance, "instance");
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(variables, "variables");

    MutableState state = resolveState(instance);
    if (!Instance.STATE_WAITING.equals(state.state)) {
      throw new BpmnException(
          "unknown_task", "Instance is not waiting; cannot complete task: " + taskId);
    }

    ActiveUserTask found = null;
    for (Iterator<ActiveUserTask> it = state.waitingUserTasks.iterator(); it.hasNext(); ) {
      ActiveUserTask ut = it.next();
      if (ut.taskId.equals(taskId)) {
        found = ut;
        it.remove();
        break;
      }
    }

    if (found == null) {
      throw new BpmnException("unknown_task", "Task is not active on instance: " + taskId);
    }

    state.waitingTimers.removeIf(t -> taskId.equals(t.attachedToTaskId));
    state.variables.putAll(variables);
    state.state = Instance.STATE_RUNNING;

    Deque<Token> queue = new ArrayDeque<>();
    enqueueOutgoing(state.definition, found.node, state.variables, queue);
    advance(state, queue, null, false);
  }

  /**
   * Evaluates all waiting timers across every tracked instance and fires those whose due instant is
   * at or before {@code clock.now()}.
   */
  public void tick() {
    Instant now = clock.now();
    List<MutableState> snapshot = new ArrayList<>(trackedInstances.values());
    for (MutableState state : snapshot) {
      if (!Instance.STATE_WAITING.equals(state.state) || state.waitingTimers.isEmpty()) {
        continue;
      }
      List<ActiveTimer> dueTimers = new ArrayList<>();
      for (ActiveTimer timer : state.waitingTimers) {
        if (!timer.dueAt.isAfter(now)) {
          dueTimers.add(timer);
        }
      }
      if (dueTimers.isEmpty()) {
        continue;
      }

      Deque<Token> queue = new ArrayDeque<>();
      for (ActiveTimer timer : dueTimers) {
        if (!state.waitingTimers.remove(timer)) {
          continue;
        }
        FlowNode timerNode = state.definition.nodes().get(timer.nodeId);
        if (timer.attachedToTaskId == null) {
          recordEvent(state, now, timerNode);
          enqueueOutgoing(state.definition, timerNode, state.variables, queue);
        } else {
          recordEvent(state, now, timerNode);
          if (timer.cancelActivity) {
            removeFirstUserTask(state, timer.attachedToTaskId);
            state.waitingTimers.removeIf(t -> timer.attachedToTaskId.equals(t.attachedToTaskId));
          }
          enqueueOutgoing(state.definition, timerNode, state.variables, queue);
        }
      }

      if (!queue.isEmpty() || (state.waitingUserTasks.isEmpty() && state.waitingTimers.isEmpty())) {
        state.state = Instance.STATE_RUNNING;
        advance(state, queue, null, false);
      } else {
        syncActiveTasks(state);
      }
    }
  }

  /**
   * Reconstructs a process instance by replaying a previously recorded audit event log against the
   * given {@link ProcessDefinition}.
   *
   * @param def the process definition
   * @param log ordered audit log to replay
   * @return the reconstructed {@link Instance}
   */
  public Instance replay(ProcessDefinition def, List<AuditEvent> log) {
    Objects.requireNonNull(def, "def");
    Objects.requireNonNull(log, "log");

    String instanceId = "inst-" + ++instanceCounter;
    MutableState state = new MutableState(instanceId, def, Instance.STATE_RUNNING);
    trackInstance(state);

    if (log.isEmpty()) {
      state.state = Instance.STATE_COMPLETED;
      return Instance.createLive(state);
    }

    ReplayCursor cursor = new ReplayCursor(log);
    FlowNode firstNode = def.nodes().get(log.get(0).activityId());
    if (firstNode == null || firstNode.kind() != NodeKind.START_EVENT) {
      throw new BpmnException("malformed", "Replay log must begin with a valid startEvent");
    }

    Deque<Token> queue = new ArrayDeque<>();
    queue.addLast(new Token(firstNode.id(), null));
    advance(state, queue, cursor, true);

    while (cursor.hasMore() && Instance.STATE_WAITING.equals(state.state)) {
      AuditEvent nextEvent = cursor.peek();
      FlowNode nextNode = def.nodes().get(nextEvent.activityId());
      if (nextNode == null) {
        throw new BpmnException(
            "malformed", "Replay log references unknown activity: " + nextEvent.activityId());
      }

      if (nextNode.kind() == NodeKind.BOUNDARY_EVENT && isTimerWaiting(state, nextNode.id())) {
        ActiveTimer timer = removeTimer(state, nextNode.id());
        AuditEvent ev = cursor.consumeExpected(nextNode.id());
        appendReplayEvent(state, ev);
        if (timer.cancelActivity) {
          removeFirstUserTask(state, timer.attachedToTaskId);
          state.waitingTimers.removeIf(t -> timer.attachedToTaskId.equals(t.attachedToTaskId));
        }
        state.state = Instance.STATE_RUNNING;
        enqueueOutgoing(def, nextNode, state.variables, queue);
        advance(state, queue, cursor, true);
        continue;
      }

      if (nextNode.kind() == NodeKind.INTERMEDIATE_CATCH_EVENT
          && isTimerWaiting(state, nextNode.id())) {
        removeTimer(state, nextNode.id());
        AuditEvent ev = cursor.consumeExpected(nextNode.id());
        appendReplayEvent(state, ev);
        state.state = Instance.STATE_RUNNING;
        enqueueOutgoing(def, nextNode, state.variables, queue);
        advance(state, queue, cursor, true);
        continue;
      }

      ActiveUserTask completedTask = findUserTaskwhichCanReach(state, nextNode.id());
      if (completedTask != null) {
        state.waitingUserTasks.remove(completedTask);
        state.waitingTimers.removeIf(t -> completedTask.taskId.equals(t.attachedToTaskId));
        state.state = Instance.STATE_RUNNING;
        enqueueOutgoingForReplay(def, completedTask.node, nextNode.id(), queue);
        advance(state, queue, cursor, true);
        continue;
      }

      break;
    }

    return Instance.createLive(state);
  }

  private void advance(
      MutableState state, Deque<Token> queue, ReplayCursor cursor, boolean isReplay) {
    ProcessDefinition def = state.definition;
    int steps = 0;

    while (!queue.isEmpty()) {
      if (isReplay && cursor != null && !cursor.hasMore()) {
        break;
      }
      steps++;
      if (steps > STEP_LIMIT) {
        state.state = Instance.STATE_FAILED;
        throw new BpmnException(
            "step_limit", "Execution exceeded maximum step limit of " + STEP_LIMIT);
      }

      Token token = queue.removeFirst();
      FlowNode node = def.nodes().get(token.nodeId);
      Instant now = isReplay && cursor != null ? cursor.peek().at() : clock.now();

      switch (node.kind()) {
        case START_EVENT -> {
          recordOrReplayEvent(state, now, node, cursor, isReplay);
          enqueueOutgoingForStep(def, node, state.variables, queue, cursor, isReplay);
        }
        case SERVICE_TASK -> {
          ServiceHandler handler = handlers.get(node.id());
          if (handler == null) {
            state.state = Instance.STATE_FAILED;
            throw new BpmnException(
                "unknown_handler", "No ServiceHandler registered for serviceTask: " + node.id());
          }
          recordOrReplayEvent(state, now, node, cursor, isReplay);
          Map<String, Object> updates =
              handler.execute(node.id(), Collections.unmodifiableMap(state.variables));
          if (updates != null && !updates.isEmpty()) {
            state.variables.putAll(updates);
          }
          enqueueOutgoingForStep(def, node, state.variables, queue, cursor, isReplay);
        }
        case USER_TASK -> {
          recordOrReplayEvent(state, now, node, cursor, isReplay);
          state.waitingUserTasks.add(new ActiveUserTask(node.id(), node));
          for (FlowNode boundary : def.boundaryEventsFor(node.id())) {
            Instant due = boundary.timer().dueAt(now);
            state.waitingTimers.add(
                new ActiveTimer(boundary.id(), due, node.id(), boundary.cancelActivity()));
          }
        }
        case INTERMEDIATE_CATCH_EVENT -> {
          Instant due = node.timer().dueAt(now);
          if (!isReplay && !due.isAfter(now)) {
            recordOrReplayEvent(state, now, node, cursor, isReplay);
            enqueueOutgoingForStep(def, node, state.variables, queue, cursor, isReplay);
          } else {
            state.waitingTimers.add(new ActiveTimer(node.id(), due, null, true));
          }
        }
        case EXCLUSIVE_GATEWAY -> {
          recordOrReplayEvent(state, now, node, cursor, isReplay);
          if (isReplay && cursor != null && cursor.hasMore()) {
            SequenceFlow chosen =
                chooseReplayOutgoing(def, node, cursor.peek().activityId(), state.variables);
            queue.addLast(new Token(chosen.targetRef(), chosen.id()));
          } else {
            SequenceFlow chosen = chooseExclusiveOutgoing(def, node, state.variables);
            queue.addLast(new Token(chosen.targetRef(), chosen.id()));
          }
        }
        case PARALLEL_GATEWAY -> {
          List<SequenceFlow> incoming = def.incoming(node.id());
          if (incoming.size() > 1 && token.viaFlowId != null) {
            Map<String, Integer> counts =
                state.joinTokens.computeIfAbsent(node.id(), k -> new LinkedHashMap<>());
            counts.merge(token.viaFlowId, 1, Integer::sum);
            boolean allPresent = true;
            for (SequenceFlow inFlow : incoming) {
              if (counts.getOrDefault(inFlow.id(), 0) <= 0) {
                allPresent = false;
                break;
              }
            }
            if (!allPresent) {
              continue;
            }
            for (SequenceFlow inFlow : incoming) {
              int remaining = counts.get(inFlow.id()) - 1;
              if (remaining <= 0) {
                counts.remove(inFlow.id());
              } else {
                counts.put(inFlow.id(), remaining);
              }
            }
          }
          recordOrReplayEvent(state, now, node, cursor, isReplay);
          enqueueOutgoingForStep(def, node, state.variables, queue, cursor, isReplay);
        }
        case END_EVENT -> {
          recordOrReplayEvent(state, now, node, cursor, isReplay);
          if (node.terminateEnd()) {
            queue.clear();
            state.waitingUserTasks.clear();
            state.waitingTimers.clear();
            state.joinTokens.clear();
            syncActiveTasks(state);
            state.state = Instance.STATE_TERMINATED;
            return;
          }
        }
        case BOUNDARY_EVENT -> {
          recordOrReplayEvent(state, now, node, cursor, isReplay);
          enqueueOutgoingForStep(def, node, state.variables, queue, cursor, isReplay);
        }
      }
    }

    syncActiveTasks(state);
    if (!state.waitingUserTasks.isEmpty() || !state.waitingTimers.isEmpty()) {
      state.state = Instance.STATE_WAITING;
    } else if (hasPendingJoinTokens(state)) {
      state.state = Instance.STATE_WAITING;
    } else {
      state.state = Instance.STATE_COMPLETED;
    }
  }

  private static boolean hasPendingJoinTokens(MutableState state) {
    for (Map<String, Integer> map : state.joinTokens.values()) {
      for (int count : map.values()) {
        if (count > 0) {
          return true;
        }
      }
    }
    return false;
  }

  private static void enqueueOutgoing(
      ProcessDefinition def, FlowNode node, Map<String, Object> variables, Deque<Token> queue) {
    List<SequenceFlow> outgoing = def.outgoing(node.id());
    if (node.kind() == NodeKind.EXCLUSIVE_GATEWAY) {
      SequenceFlow chosen = chooseExclusiveOutgoing(def, node, variables);
      queue.addLast(new Token(chosen.targetRef(), chosen.id()));
      return;
    }
    for (SequenceFlow sf : outgoing) {
      if (sf.compiledCondition() != null) {
        if (ConditionEvaluator.evaluate(sf.compiledCondition(), variables)) {
          queue.addLast(new Token(sf.targetRef(), sf.id()));
        }
      } else {
        queue.addLast(new Token(sf.targetRef(), sf.id()));
      }
    }
  }

  private static void enqueueOutgoingForStep(
      ProcessDefinition def,
      FlowNode node,
      Map<String, Object> variables,
      Deque<Token> queue,
      ReplayCursor cursor,
      boolean isReplay) {
    if (isReplay && cursor != null && cursor.hasMore()) {
      enqueueOutgoingForReplay(def, node, cursor.peek().activityId(), queue);
    } else {
      enqueueOutgoing(def, node, variables, queue);
    }
  }

  private static void enqueueOutgoingForReplay(
      ProcessDefinition def, FlowNode node, String nextActivityId, Deque<Token> queue) {
    List<SequenceFlow> outgoing = def.outgoing(node.id());
    if (outgoing.size() <= 1 || node.kind() == NodeKind.PARALLEL_GATEWAY) {
      for (SequenceFlow sf : outgoing) {
        queue.addLast(new Token(sf.targetRef(), sf.id()));
      }
      return;
    }
    for (SequenceFlow sf : outgoing) {
      if (sf.targetRef().equals(nextActivityId)) {
        queue.addLast(new Token(sf.targetRef(), sf.id()));
        return;
      }
    }
    for (SequenceFlow sf : outgoing) {
      queue.addLast(new Token(sf.targetRef(), sf.id()));
    }
  }

  private static SequenceFlow chooseReplayOutgoing(
      ProcessDefinition def,
      FlowNode gateway,
      String nextActivityId,
      Map<String, Object> variables) {
    for (SequenceFlow sf : def.outgoing(gateway.id())) {
      if (sf.targetRef().equals(nextActivityId)) {
        return sf;
      }
    }
    return chooseExclusiveOutgoing(def, gateway, variables);
  }

  private static SequenceFlow chooseExclusiveOutgoing(
      ProcessDefinition def, FlowNode gateway, Map<String, Object> variables) {
    List<SequenceFlow> outgoing = def.outgoing(gateway.id());
    String defaultId = gateway.defaultFlowId();
    SequenceFlow defaultFlow = null;

    for (SequenceFlow sf : outgoing) {
      if (sf.id().equals(defaultId)) {
        defaultFlow = sf;
        continue;
      }
      if (sf.compiledCondition() == null
          || ConditionEvaluator.evaluate(sf.compiledCondition(), variables)) {
        return sf;
      }
    }

    if (defaultFlow != null) {
      return defaultFlow;
    }
    throw new BpmnException(
        "no_outgoing_flow",
        "No outgoing sequenceFlow condition matched on exclusiveGateway: " + gateway.id());
  }

  private static FlowNode selectStartEvent(ProcessDefinition def) {
    for (FlowNode start : def.startEvents()) {
      if (start.timer() == null) {
        return start;
      }
    }
    return def.startEvents().get(0);
  }

  private void recordOrReplayEvent(
      MutableState state, Instant now, FlowNode node, ReplayCursor cursor, boolean isReplay) {
    if (isReplay && cursor != null) {
      AuditEvent ev = cursor.consumeExpected(node.id());
      appendReplayEvent(state, ev);
    } else {
      recordEvent(state, now, node);
    }
  }

  private void recordEvent(MutableState state, Instant at, FlowNode node) {
    AuditEvent event = new AuditEvent(++state.sequence, at, node.auditKind(), node.id());
    state.log.add(event);
    onEvent.accept(event);
  }

  private void appendReplayEvent(MutableState state, AuditEvent ev) {
    state.sequence = Math.max(state.sequence + 1, ev.sequence());
    state.log.add(ev);
    onEvent.accept(ev);
  }

  private static void syncActiveTasks(MutableState state) {
    state.activeTasks.clear();
    for (ActiveUserTask ut : state.waitingUserTasks) {
      state.activeTasks.add(ut.taskId);
    }
    if (state.waitingUserTasks.isEmpty()) {
      for (ActiveTimer timer : state.waitingTimers) {
        if (timer.attachedToTaskId == null) {
          state.activeTasks.add(timer.nodeId);
        }
      }
    }
  }

  private static void removeFirstUserTask(MutableState state, String taskId) {
    for (Iterator<ActiveUserTask> it = state.waitingUserTasks.iterator(); it.hasNext(); ) {
      if (it.next().taskId.equals(taskId)) {
        it.remove();
        return;
      }
    }
  }

  private static boolean isTimerWaiting(MutableState state, String nodeId) {
    for (ActiveTimer t : state.waitingTimers) {
      if (t.nodeId.equals(nodeId)) {
        return true;
      }
    }
    return false;
  }

  private static ActiveTimer removeTimer(MutableState state, String nodeId) {
    for (Iterator<ActiveTimer> it = state.waitingTimers.iterator(); it.hasNext(); ) {
      ActiveTimer t = it.next();
      if (t.nodeId.equals(nodeId)) {
        it.remove();
        return t;
      }
    }
    throw new BpmnException("malformed", "Timer not waiting during replay: " + nodeId);
  }

  private static ActiveUserTask findUserTaskwhichCanReach(MutableState state, String targetNodeId) {
    for (ActiveUserTask ut : state.waitingUserTasks) {
      for (SequenceFlow sf : state.definition.outgoing(ut.taskId)) {
        if (sf.targetRef().equals(targetNodeId)) {
          return ut;
        }
      }
    }
    if (!state.waitingUserTasks.isEmpty()) {
      return state.waitingUserTasks.get(0);
    }
    return null;
  }

  private MutableState resolveState(Instance instance) {
    MutableState direct = instance.mutableStateOrNull();
    if (direct != null && trackedInstances.containsKey(direct.id)) {
      return direct;
    }
    MutableState lookup = trackedInstances.get(instance.id());
    if (lookup != null) {
      return lookup;
    }
    throw new BpmnException("unknown_task", "Unknown process instance: " + instance.id());
  }

  private void trackInstance(MutableState state) {
    if (trackedInstances.size() >= MAX_TRACKED_INSTANCES) {
      Iterator<Map.Entry<String, MutableState>> it = trackedInstances.entrySet().iterator();
      while (it.hasNext() && trackedInstances.size() >= MAX_TRACKED_INSTANCES) {
        Map.Entry<String, MutableState> entry = it.next();
        String st = entry.getValue().state;
        if (Instance.STATE_COMPLETED.equals(st)
            || Instance.STATE_TERMINATED.equals(st)
            || Instance.STATE_FAILED.equals(st)) {
          it.remove();
        }
      }
      if (trackedInstances.size() >= MAX_TRACKED_INSTANCES && it.hasNext()) {
        Iterator<String> firstIt = trackedInstances.keySet().iterator();
        if (firstIt.hasNext()) {
          firstIt.next();
          firstIt.remove();
        }
      }
    }
    trackedInstances.put(state.id, state);
  }

  private record Token(String nodeId, String viaFlowId) {}

  record ActiveUserTask(String taskId, FlowNode node) {}

  record ActiveTimer(
      String nodeId, Instant dueAt, String attachedToTaskId, boolean cancelActivity) {}

  private static final class ReplayCursor {
    private final List<AuditEvent> events;
    private int index = 0;

    ReplayCursor(List<AuditEvent> events) {
      this.events = events;
    }

    boolean hasMore() {
      return index < events.size();
    }

    AuditEvent peek() {
      return events.get(index);
    }

    AuditEvent consumeExpected(String activityId) {
      if (!hasMore()) {
        throw new BpmnException("malformed", "Replay log ended unexpectedly before " + activityId);
      }
      AuditEvent ev = events.get(index);
      if (!ev.activityId().equals(activityId)) {
        for (int j = index; j < events.size(); j++) {
          if (events.get(j).activityId().equals(activityId)) {
            return events.get(index++);
          }
        }
        throw new BpmnException(
            "malformed",
            "Replay log mismatch: expected '"
                + activityId
                + "' but found '"
                + ev.activityId()
                + "'");
      }
      index++;
      return ev;
    }
  }
}
