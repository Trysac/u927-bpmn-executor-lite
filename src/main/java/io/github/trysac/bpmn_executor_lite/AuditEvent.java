package io.github.trysac.bpmn_executor_lite;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable audit log entry recorded during process execution.
 *
 * <p>Variable values are never stored in audit events to prevent sensitive data leakage.
 *
 * @param sequence monotonic 1-based sequence number within the process instance
 * @param at timestamp from the engine {@link Clock} when the event occurred
 * @param kind event kind (for example {@code "start"}, {@code "serviceTask"}, {@code "userTask"})
 * @param activityId BPMN element identifier
 */
public record AuditEvent(long sequence, Instant at, String kind, String activityId) {

  /** Creates and validates an {@link AuditEvent}. */
  public AuditEvent {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(activityId, "activityId");
  }
}
