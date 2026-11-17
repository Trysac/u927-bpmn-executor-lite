package io.github.trysac.bpmn_executor_lite;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Process instance representing an execution of a {@link ProcessDefinition}.
 *
 * <p>Valid states are {@code "RUNNING"}, {@code "WAITING"}, {@code "COMPLETED"}, {@code
 * "TERMINATED"}, and {@code "FAILED"}.
 *
 * @param id unique identifier of this process instance
 * @param state current lifecycle state of the instance
 * @param variables process variables associated with the instance
 * @param activeTasks ordered list of currently waiting user task or timer element ids
 * @param log ordered list of audit events recorded for this instance
 */
public record Instance(
    String id,
    String state,
    Map<String, Object> variables,
    List<String> activeTasks,
    List<AuditEvent> log) {

  /** State constant for a currently executing instance. */
  public static final String STATE_RUNNING = "RUNNING";

  /** State constant for an instance waiting on a user task or timer. */
  public static final String STATE_WAITING = "WAITING";

  /** State constant for an instance that finished normally. */
  public static final String STATE_COMPLETED = "COMPLETED";

  /** State constant for an instance stopped by a terminate end event. */
  public static final String STATE_TERMINATED = "TERMINATED";

  /** State constant for an instance that failed during execution. */
  public static final String STATE_FAILED = "FAILED";

  /**
   * Creates an {@link Instance} record.
   *
   * @param id instance identifier
   * @param state initial or static state
   * @param variables variables map
   * @param activeTasks active task ids
   * @param log audit event log
   */
  public Instance {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(variables, "variables");
    Objects.requireNonNull(activeTasks, "activeTasks");
    Objects.requireNonNull(log, "log");
  }

  static Instance createLive(MutableState mutable) {
    return new Instance(
        mutable.id,
        mutable.state,
        new LiveMapView(mutable),
        new LiveListView<>(mutable, true),
        new LiveListView<>(mutable, false));
  }

  /**
   * Returns the current lifecycle state of this instance.
   *
   * @return the current state
   */
  @Override
  public String state() {
    if (variables instanceof LiveMapView live) {
      return live.mutable.state;
    }
    return state;
  }

  /**
   * Returns an unmodifiable snapshot of the process variables.
   *
   * @return the process variables
   */
  @Override
  public Map<String, Object> variables() {
    if (variables instanceof LiveMapView live) {
      return Collections.unmodifiableMap(new LinkedHashMap<>(live.mutable.variables));
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
  }

  /**
   * Returns the ids of the currently waiting user tasks or timers, in order.
   *
   * @return the active task ids
   */
  @Override
  public List<String> activeTasks() {
    if (variables instanceof LiveMapView live) {
      return List.copyOf(live.mutable.activeTasks);
    }
    return List.copyOf(activeTasks);
  }

  /**
   * Returns the audit events recorded for this instance, in order.
   *
   * @return the audit log
   */
  @Override
  public List<AuditEvent> log() {
    if (variables instanceof LiveMapView live) {
      return List.copyOf(live.mutable.log);
    }
    return List.copyOf(log);
  }

  MutableState mutableStateOrNull() {
    if (variables instanceof LiveMapView live) {
      return live.mutable;
    }
    return null;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Instance other)) {
      return false;
    }
    return Objects.equals(id(), other.id())
        && Objects.equals(state(), other.state())
        && Objects.equals(variables(), other.variables())
        && Objects.equals(activeTasks(), other.activeTasks())
        && Objects.equals(log(), other.log());
  }

  @Override
  public int hashCode() {
    return Objects.hash(id(), state(), variables(), activeTasks(), log());
  }

  @Override
  public String toString() {
    return "Instance[id="
        + id()
        + ", state="
        + state()
        + ", variables="
        + variables()
        + ", activeTasks="
        + activeTasks()
        + ", log="
        + log()
        + "]";
  }

  static final class MutableState {
    final String id;
    final ProcessDefinition definition;
    String state;
    final Map<String, Object> variables = new LinkedHashMap<>();
    final List<String> activeTasks = new ArrayList<>();
    final List<AuditEvent> log = new ArrayList<>();
    long sequence = 0L;

    final List<Engine.ActiveUserTask> waitingUserTasks = new ArrayList<>();
    final List<Engine.ActiveTimer> waitingTimers = new ArrayList<>();
    final Map<String, Map<String, Integer>> joinTokens = new LinkedHashMap<>();

    MutableState(String id, ProcessDefinition definition, String state) {
      this.id = id;
      this.definition = definition;
      this.state = state;
    }
  }

  private static final class LiveMapView extends AbstractMap<String, Object> {
    final MutableState mutable;

    LiveMapView(MutableState mutable) {
      this.mutable = mutable;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
      return Collections.unmodifiableMap(mutable.variables).entrySet();
    }
  }

  private static final class LiveListView<E> extends AbstractList<E> {
    private final MutableState mutable;
    private final boolean isTasks;

    LiveListView(MutableState mutable, boolean isTasks) {
      this.mutable = mutable;
      this.isTasks = isTasks;
    }

    @SuppressWarnings("unchecked")
    private List<E> delegate() {
      return (List<E>) (isTasks ? mutable.activeTasks : mutable.log);
    }

    @Override
    public E get(int index) {
      return delegate().get(index);
    }

    @Override
    public int size() {
      return delegate().size();
    }
  }
}
