package io.github.trysac.bpmn_executor_lite;

import java.util.Objects;

/**
 * Typed exception thrown when BPMN parsing, condition evaluation, or process execution fails.
 *
 * <p>Every exception carries a stable machine-readable {@link #code()}.
 */
public final class BpmnException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;
  private static final int MAX_MESSAGE_LENGTH = 200;

  private final String code;

  /**
   * Creates a new BPMN exception with the given error code and message.
   *
   * @param code stable machine-readable error code
   * @param message human-readable description (truncated to 200 characters if longer)
   */
  public BpmnException(String code, String message) {
    super(truncate(Objects.requireNonNull(message, "message")));
    this.code = Objects.requireNonNull(code, "code");
  }

  /**
   * Creates a new BPMN exception with the given error code, message, and cause.
   *
   * @param code stable machine-readable error code
   * @param message human-readable description (truncated to 200 characters if longer)
   * @param cause underlying cause
   */
  public BpmnException(String code, String message, Throwable cause) {
    super(truncate(Objects.requireNonNull(message, "message")), cause);
    this.code = Objects.requireNonNull(code, "code");
  }

  /**
   * Returns the machine-readable error code.
   *
   * @return the error code
   */
  public String code() {
    return code;
  }

  private static String truncate(String msg) {
    if (msg.length() <= MAX_MESSAGE_LENGTH) {
      return msg;
    }
    return msg.substring(0, MAX_MESSAGE_LENGTH);
  }
}
