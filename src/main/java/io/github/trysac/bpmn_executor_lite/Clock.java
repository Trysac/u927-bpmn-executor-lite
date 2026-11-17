package io.github.trysac.bpmn_executor_lite;

import java.time.Instant;

/** Clock abstraction supplying the current instant for audit timestamps and timer evaluation. */
@FunctionalInterface
public interface Clock {

  /**
   * Returns the current instant.
   *
   * @return the current instant, never null
   */
  Instant now();
}
