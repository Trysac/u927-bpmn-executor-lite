package io.github.trysac.bpmn_executor_lite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/** Property-based tests using jqwik for condition evaluation and BPMN execution invariants. */
class BpmnPropertiesTest {

  @Property
  void orderProcessBranchesDeterministicallyAroundThreshold(
      @ForAll @IntRange(min = -10_000, max = 10_000) int amount) {
    String xml =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="order">
            <startEvent id="start"/>
            <serviceTask id="check"/>
            <exclusiveGateway id="gw" default="toShip"/>
            <userTask id="approve"/>
            <serviceTask id="ship"/>
            <endEvent id="end"/>
            <sequenceFlow id="f1" sourceRef="start" targetRef="check"/>
            <sequenceFlow id="f2" sourceRef="check" targetRef="gw"/>
            <sequenceFlow id="toApprove" sourceRef="gw" targetRef="approve">
              <conditionExpression>amount &gt; 100</conditionExpression>
            </sequenceFlow>
            <sequenceFlow id="toShip" sourceRef="gw" targetRef="ship"/>
            <sequenceFlow id="f3" sourceRef="approve" targetRef="ship"/>
            <sequenceFlow id="f4" sourceRef="ship" targetRef="end"/>
          </process>
        </definitions>
        """;
    ProcessDefinition def = BpmnParser.parse(xml.getBytes(StandardCharsets.UTF_8));
    Clock clock = () -> Instant.EPOCH;
    Engine engine =
        new Engine(
            clock,
            Map.of(
                "check", (id, vars) -> Map.of(),
                "ship", (id, vars) -> Map.of()),
            null);

    Instance instance = engine.start(def, Map.of("amount", amount));
    if (amount > 100) {
      assertEquals("WAITING", instance.state());
      assertEquals(java.util.List.of("approve"), instance.activeTasks());
    } else {
      assertEquals("COMPLETED", instance.state());
      assertEquals(java.util.List.of(), instance.activeTasks());
    }
  }

  @Property
  void conditionEvaluatorMatchesJavaIntegerComparisons(
      @ForAll @IntRange(min = -1000, max = 1000) int x,
      @ForAll @IntRange(min = -1000, max = 1000) int y) {
    Map<String, Object> vars = Map.of("x", x);
    ConditionEvaluator.Expr expr =
        ConditionEvaluator.compile("(x == " + y + " or x < " + y + ") and not (x > " + y + ")");
    assertEquals(x <= y, ConditionEvaluator.evaluate(expr, vars));
  }
}
