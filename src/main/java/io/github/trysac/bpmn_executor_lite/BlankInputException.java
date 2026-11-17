package io.github.trysac.bpmn_executor_lite;

/** Legacy exception retained for backwards compatibility with the template. */
final class BlankInputException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  BlankInputException() {
    super("input is blank");
  }
}
