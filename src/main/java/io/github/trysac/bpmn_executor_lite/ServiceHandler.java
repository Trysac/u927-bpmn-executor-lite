package io.github.trysac.bpmn_executor_lite;

import java.util.Map;

/** Handler invoked synchronously when execution reaches a BPMN {@code serviceTask}. */
@FunctionalInterface
public interface ServiceHandler {

  /**
   * Executes the service task logic and returns variable updates to merge into the instance.
   *
   * @param taskId the BPMN element id of the service task
   * @param variables an unmodifiable snapshot of the current process variables
   * @return a map of variable updates to merge (may be empty or null)
   */
  Map<String, Object> execute(String taskId, Map<String, Object> variables);
}
