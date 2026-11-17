package io.github.trysac.bpmn_executor_lite;

import java.util.Locale;
import java.util.Objects;

/** Internal text helper retained from template. */
final class Text {

  private Text() {}

  static String normalize(String input) {
    Objects.requireNonNull(input, "input");
    String trimmed = input.strip();
    if (trimmed.isEmpty()) {
      throw new BlankInputException();
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }
}
