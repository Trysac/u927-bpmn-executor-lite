package io.github.trysac.bpmn_executor_lite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;

/** Property tests for internal Text helper. */
class TextPropertiesTest {

  @Property
  void normalizeIsIdempotent(@ForAll @AlphaChars @StringLength(min = 1, max = 32) String value) {
    String once = Text.normalize("  " + value + "  ");
    assertEquals(once, Text.normalize(once));
  }
}
