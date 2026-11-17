package io.github.trysac.bpmn_executor_lite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit and acceptance tests for {@link BpmnParser}, {@link Engine}, {@link
 * ConditionEvaluator}, and {@link BpmnException}.
 */
class BpmnExecutorTest {

  static final class ManualClock implements Clock {
    private Instant current;

    ManualClock(Instant initial) {
      this.current = initial;
    }

    @Override
    public Instant now() {
      return current;
    }

    void advance(Duration duration) {
      this.current = this.current.plus(duration);
    }
  }

  private static byte[] readFixture(String name) {
    try (InputStream in = BpmnExecutorTest.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalStateException("Missing fixture: " + name);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void orderFixtureWithAmount50CompletesDirectlyThroughDefaultFlow() {
    ProcessDefinition def = BpmnParser.parse(readFixture("order.bpmn.xml"));
    ManualClock clock = new ManualClock(Instant.parse("2025-01-01T00:00:00Z"));
    List<AuditEvent> emitted = new ArrayList<>();
    Engine engine =
        new Engine(
            clock,
            Map.of(
                "check", (id, vars) -> Map.of("checked", true),
                "ship", (id, vars) -> Map.of("shipped", true)),
            emitted::add);

    Instance instance = engine.start(def, Map.of("amount", 50));

    assertEquals("COMPLETED", instance.state());
    assertEquals(List.of(), instance.activeTasks());
    List<String> activityIds = instance.log().stream().map(AuditEvent::activityId).toList();
    assertEquals(List.of("start", "check", "gw", "ship", "end"), activityIds);
    assertEquals(instance.log(), emitted);
    assertEquals(Boolean.TRUE, instance.variables().get("shipped"));
  }

  @Test
  void orderFixtureWithAmount150WaitsAtApproveAndCompletesToShipAndEnd() {
    ProcessDefinition def = BpmnParser.parse(readFixture("order.bpmn.xml"));
    ManualClock clock = new ManualClock(Instant.parse("2025-01-01T00:00:00Z"));
    Engine engine =
        new Engine(
            clock,
            Map.of(
                "check", (id, vars) -> Map.of(),
                "ship", (id, vars) -> Map.of("shipped", true)),
            null);

    Instance instance = engine.start(def, Map.of("amount", 150));

    assertEquals("WAITING", instance.state());
    assertEquals(List.of("approve"), instance.activeTasks());

    engine.complete(instance, "approve", Map.of("approved", true));

    assertEquals("COMPLETED", instance.state());
    assertEquals(List.of(), instance.activeTasks());
    List<String> activityIds = instance.log().stream().map(AuditEvent::activityId).toList();
    assertEquals(List.of("start", "check", "gw", "approve", "ship", "end"), activityIds);
    assertEquals(Boolean.TRUE, instance.variables().get("approved"));
  }

  @Test
  void replayReconstructsWaitingInstanceWithRecordEquality() {
    ProcessDefinition def = BpmnParser.parse(readFixture("order.bpmn.xml"));
    ManualClock clock = new ManualClock(Instant.parse("2025-01-01T00:00:00Z"));
    Map<String, ServiceHandler> handlers =
        Map.of(
            "check", (id, vars) -> Map.of("amount", 150),
            "ship", (id, vars) -> Map.of());

    Engine engine1 = new Engine(clock, handlers, null);
    Instance original = engine1.start(def, Map.of("amount", 150));
    assertEquals("WAITING", original.state());
    assertEquals(List.of("approve"), original.activeTasks());

    Engine engine2 = new Engine(clock, handlers, null);
    Instance replayed = engine2.replay(def, original.log());

    assertEquals("WAITING", replayed.state());
    assertEquals(List.of("approve"), replayed.activeTasks());
    assertEquals(original, replayed);
    assertEquals(original.hashCode(), replayed.hashCode());
    assertEquals(original.toString(), replayed.toString());

    engine2.complete(replayed, "approve", Map.of("approved", true));
    assertEquals("COMPLETED", replayed.state());

    Engine engine3 = new Engine(clock, handlers, null);
    Instance replayedCompleted = engine3.replay(def, replayed.log());
    assertEquals("COMPLETED", replayedCompleted.state());
  }

  @Test
  void parallelGatewayWaitsForBothTokensAndFiresJoinExactlyOnce() {
    ProcessDefinition def = BpmnParser.parse(readFixture("parallel.bpmn.xml"));
    ManualClock clock = new ManualClock(Instant.parse("2025-01-01T00:00:00Z"));
    Engine engine = new Engine(clock, Map.of(), null);

    Instance instance = engine.start(def, Map.of());
    assertEquals("WAITING", instance.state());
    assertEquals(List.of("a", "b"), instance.activeTasks());

    engine.complete(instance, "a", Map.of("aDone", 1));
    assertEquals("WAITING", instance.state());
    assertEquals(List.of("b"), instance.activeTasks());
    assertFalse(
        instance.log().stream().anyMatch(e -> "join".equals(e.activityId())),
        "Join must not fire with only 1 of 2 tokens");

    engine.complete(instance, "b", Map.of("bDone", 1));
    assertEquals("COMPLETED", instance.state());
    assertEquals(List.of(), instance.activeTasks());
    long joinCount = instance.log().stream().filter(e -> "join".equals(e.activityId())).count();
    assertEquals(1L, joinCount);
  }

  @Test
  void timersIntermediateCatchAndInterruptingBoundaryEvent() {
    ProcessDefinition def = BpmnParser.parse(readFixture("timers.bpmn.xml"));
    ManualClock clock = new ManualClock(Instant.parse("2025-01-01T00:00:00Z"));
    Engine engine =
        new Engine(clock, Map.of("escalate", (id, vars) -> Map.of("escalated", true)), null);

    Instance instance = engine.start(def, Map.of());
    assertEquals("WAITING", instance.state());
    assertEquals(List.of("wait5m"), instance.activeTasks());

    clock.advance(Duration.ofSeconds(299));
    engine.tick();
    assertEquals("WAITING", instance.state());
    assertEquals(List.of("wait5m"), instance.activeTasks());

    clock.advance(Duration.ofSeconds(1));
    engine.tick();
    assertEquals("WAITING", instance.state());
    assertEquals(List.of("review"), instance.activeTasks());

    clock.advance(Duration.ofMinutes(59));
    engine.tick();
    assertEquals("WAITING", instance.state());
    assertEquals(List.of("review"), instance.activeTasks());

    clock.advance(Duration.ofMinutes(1));
    engine.tick();
    assertEquals("COMPLETED", instance.state());
    assertEquals(List.of(), instance.activeTasks());
    assertEquals(Boolean.TRUE, instance.variables().get("escalated"));
    List<String> ids = instance.log().stream().map(AuditEvent::activityId).toList();
    assertEquals(List.of("start", "wait5m", "review", "reviewTimeout", "escalate", "end"), ids);

    BpmnException ex =
        assertThrows(BpmnException.class, () -> engine.complete(instance, "review", Map.of()));
    assertEquals("unknown_task", ex.code());
  }

  @Test
  void nonInterruptingBoundaryTimerKeepsTaskActiveAndRunsParallelPath() {
    String xml =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p">
            <startEvent id="start"/>
            <userTask id="work"/>
            <boundaryEvent id="remind" attachedToRef="work" cancelActivity="false">
              <timerEventDefinition>
                <timeDuration>PT10M</timeDuration>
              </timerEventDefinition>
            </boundaryEvent>
            <userTask id="notify"/>
            <endEvent id="end1"/>
            <endEvent id="end2"/>
            <sequenceFlow id="f1" sourceRef="start" targetRef="work"/>
            <sequenceFlow id="f2" sourceRef="work" targetRef="end1"/>
            <sequenceFlow id="f3" sourceRef="remind" targetRef="notify"/>
            <sequenceFlow id="f4" sourceRef="notify" targetRef="end2"/>
          </process>
        </definitions>
        """;
    ProcessDefinition def = BpmnParser.parse(xml.getBytes(StandardCharsets.UTF_8));
    ManualClock clock = new ManualClock(Instant.parse("2025-01-01T00:00:00Z"));
    Engine engine = new Engine(clock, Map.of(), null);

    Instance instance = engine.start(def, Map.of());
    assertEquals(List.of("work"), instance.activeTasks());

    clock.advance(Duration.ofMinutes(10));
    engine.tick();

    assertEquals("WAITING", instance.state());
    assertEquals(List.of("work", "notify"), instance.activeTasks());

    engine.complete(instance, "notify", Map.of());
    assertEquals("WAITING", instance.state());
    assertEquals(List.of("work"), instance.activeTasks());

    engine.complete(instance, "work", Map.of());
    assertEquals("COMPLETED", instance.state());
    assertEquals(List.of(), instance.activeTasks());
  }

  @Test
  void terminateEndEventStopsAllWaitingTokensImmediately() {
    String xml =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p">
            <startEvent id="start"/>
            <parallelGateway id="fork"/>
            <userTask id="waitTask"/>
            <userTask id="abortTask"/>
            <endEvent id="normalEnd"/>
            <endEvent id="termEnd">
              <terminateEventDefinition/>
            </endEvent>
            <sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
            <sequenceFlow id="f2" sourceRef="fork" targetRef="waitTask"/>
            <sequenceFlow id="f3" sourceRef="fork" targetRef="abortTask"/>
            <sequenceFlow id="f4" sourceRef="waitTask" targetRef="normalEnd"/>
            <sequenceFlow id="f5" sourceRef="abortTask" targetRef="termEnd"/>
          </process>
        </definitions>
        """;
    ProcessDefinition def = BpmnParser.parse(xml.getBytes(StandardCharsets.UTF_8));
    ManualClock clock = new ManualClock(Instant.parse("2025-01-01T00:00:00Z"));
    Engine engine = new Engine(clock, Map.of(), null);

    Instance instance = engine.start(def, Map.of());
    assertEquals("WAITING", instance.state());
    assertEquals(List.of("waitTask", "abortTask"), instance.activeTasks());

    engine.complete(instance, "abortTask", Map.of());
    assertEquals("TERMINATED", instance.state());
    assertEquals(List.of(), instance.activeTasks());
  }

  @Test
  void timerAlreadyDueAtStartProceedsImmediately() {
    String xml =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p">
            <startEvent id="start">
              <timerEventDefinition>
                <timeDate>2024-12-31T23:59:00Z</timeDate>
              </timerEventDefinition>
            </startEvent>
            <intermediateCatchEvent id="catchNow">
              <timerEventDefinition>
                <timeDuration>PT0S</timeDuration>
              </timerEventDefinition>
            </intermediateCatchEvent>
            <endEvent id="end"/>
            <sequenceFlow id="f1" sourceRef="start" targetRef="catchNow"/>
            <sequenceFlow id="f2" sourceRef="catchNow" targetRef="end"/>
          </process>
        </definitions>
        """;
    ProcessDefinition def = BpmnParser.parse(xml.getBytes(StandardCharsets.UTF_8));
    ManualClock clock = new ManualClock(Instant.parse("2025-01-01T00:00:00Z"));
    Engine engine = new Engine(clock, Map.of(), null);

    Instance instance = engine.start(def, Map.of());
    assertEquals("COMPLETED", instance.state());
    assertEquals(
        List.of("start", "catchNow", "end"),
        instance.log().stream().map(AuditEvent::activityId).toList());
  }

  @Test
  void processWithTwoStartEventsRefusedUnlessOneIsTimer() {
    String twoNoneStarts =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p">
            <startEvent id="s1"/>
            <startEvent id="s2"/>
            <endEvent id="e"/>
            <sequenceFlow id="f1" sourceRef="s1" targetRef="e"/>
            <sequenceFlow id="f2" sourceRef="s2" targetRef="e"/>
          </process>
        </definitions>
        """;
    BpmnException err =
        assertThrows(
            BpmnException.class,
            () -> BpmnParser.parse(twoNoneStarts.getBytes(StandardCharsets.UTF_8)));
    assertEquals("malformed", err.code());

    String oneNoneOneTimer =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p">
            <startEvent id="s1"/>
            <startEvent id="s2">
              <timerEventDefinition>
                <timeDuration>PT5M</timeDuration>
              </timerEventDefinition>
            </startEvent>
            <endEvent id="e"/>
            <sequenceFlow id="f1" sourceRef="s1" targetRef="e"/>
            <sequenceFlow id="f2" sourceRef="s2" targetRef="e"/>
          </process>
        </definitions>
        """;
    ProcessDefinition def = BpmnParser.parse(oneNoneOneTimer.getBytes(StandardCharsets.UTF_8));
    ManualClock clock = new ManualClock(Instant.parse("2025-01-01T00:00:00Z"));
    Engine engine = new Engine(clock, Map.of(), null);
    Instance inst = engine.start(def, Map.of());
    assertEquals("COMPLETED", inst.state());
  }

  @Test
  void errorCodesAndAcceptanceRejectionCases() {
    // 1. inclusiveGateway -> unsupported_element naming "inclusiveGateway"
    String inclusiveXml =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p">
            <startEvent id="s"/>
            <inclusiveGateway id="ig"/>
          </process>
        </definitions>
        """;
    BpmnException e1 =
        assertThrows(
            BpmnException.class,
            () -> BpmnParser.parse(inclusiveXml.getBytes(StandardCharsets.UTF_8)));
    assertEquals("unsupported_element", e1.code());
    assertTrue(e1.getMessage().contains("inclusiveGateway"));

    // 2. exclusiveGateway with no true condition and no default -> no_outgoing_flow
    String noOutgoingXml =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p">
            <startEvent id="s"/>
            <exclusiveGateway id="gw"/>
            <endEvent id="e"/>
            <sequenceFlow id="f1" sourceRef="s" targetRef="gw"/>
            <sequenceFlow id="f2" sourceRef="gw" targetRef="e">
              <conditionExpression>x == 1</conditionExpression>
            </sequenceFlow>
          </process>
        </definitions>
        """;
    ProcessDefinition noOutDef = BpmnParser.parse(noOutgoingXml.getBytes(StandardCharsets.UTF_8));
    ManualClock clock = new ManualClock(Instant.parse("2025-01-01T00:00:00Z"));
    Engine engine = new Engine(clock, Map.of(), null);
    BpmnException e2 =
        assertThrows(BpmnException.class, () -> engine.start(noOutDef, Map.of("x", 2)));
    assertEquals("no_outgoing_flow", e2.code());

    // 3. DOCTYPE -> doctype_forbidden
    String doctypeXml =
        """
        <?xml version="1.0"?>
        <!DOCTYPE definitions [
          <!ELEMENT definitions ANY>
        ]>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p"><startEvent id="s"/></process>
        </definitions>
        """;
    BpmnException e3 =
        assertThrows(
            BpmnException.class,
            () -> BpmnParser.parse(doctypeXml.getBytes(StandardCharsets.UTF_8)));
    assertEquals("doctype_forbidden", e3.code());

    // 4. File of 1_048_577 bytes -> input_too_large
    byte[] oversized = new byte[1_048_577];
    Arrays.fill(oversized, (byte) ' ');
    BpmnException e4 = assertThrows(BpmnException.class, () -> BpmnParser.parse(oversized));
    assertEquals("input_too_large", e4.code());

    // 5. engine.complete(instance, "nope", Map.of()) -> unknown_task
    ProcessDefinition orderDef = BpmnParser.parse(readFixture("order.bpmn.xml"));
    Engine orderEngine =
        new Engine(clock, Map.of("check", (id, v) -> Map.of(), "ship", (id, v) -> Map.of()), null);
    Instance waitingInst = orderEngine.start(orderDef, Map.of("amount", 150));
    BpmnException e5 =
        assertThrows(
            BpmnException.class, () -> orderEngine.complete(waitingInst, "nope", Map.of()));
    assertEquals("unknown_task", e5.code());

    // 6. Cycle of service tasks -> step_limit after 10_000 steps
    String cycleXml =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p">
            <startEvent id="s"/>
            <serviceTask id="t1"/>
            <serviceTask id="t2"/>
            <sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
            <sequenceFlow id="f2" sourceRef="t1" targetRef="t2"/>
            <sequenceFlow id="f3" sourceRef="t2" targetRef="t1"/>
          </process>
        </definitions>
        """;
    ProcessDefinition cycleDef = BpmnParser.parse(cycleXml.getBytes(StandardCharsets.UTF_8));
    Engine cycleEngine =
        new Engine(clock, Map.of("t1", (id, v) -> Map.of(), "t2", (id, v) -> Map.of()), null);
    BpmnException e6 =
        assertThrows(BpmnException.class, () -> cycleEngine.start(cycleDef, Map.of()));
    assertEquals("step_limit", e6.code());
  }

  @Test
  void xmlDepthLimitUnknownHandlerInvalidDurationAndConditionErrors() {
    // Depth limit > 64 -> too_deep
    StringBuilder deep = new StringBuilder();
    deep.append("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\">\n");
    deep.append("<process id=\"p\"><documentation>");
    for (int i = 0; i < 65; i++) {
      deep.append("<a>");
    }
    for (int i = 0; i < 65; i++) {
      deep.append("</a>");
    }
    deep.append("</documentation><startEvent id=\"s\"/></process></definitions>");
    BpmnException deepErr =
        assertThrows(
            BpmnException.class,
            () -> BpmnParser.parse(deep.toString().getBytes(StandardCharsets.UTF_8)));
    assertEquals("too_deep", deepErr.code());

    // Invalid duration -> invalid_duration
    String badDurXml =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p">
            <startEvent id="s">
              <timerEventDefinition>
                <timeDuration>NOT_A_DURATION</timeDuration>
              </timerEventDefinition>
            </startEvent>
          </process>
        </definitions>
        """;
    BpmnException durErr =
        assertThrows(
            BpmnException.class,
            () -> BpmnParser.parse(badDurXml.getBytes(StandardCharsets.UTF_8)));
    assertEquals("invalid_duration", durErr.code());

    // Method call in condition -> invalid_condition
    String badCondXml =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p">
            <startEvent id="s"/>
            <endEvent id="e"/>
            <sequenceFlow id="f1" sourceRef="s" targetRef="e">
              <conditionExpression>amount.intValue() &gt; 0</conditionExpression>
            </sequenceFlow>
          </process>
        </definitions>
        """;
    BpmnException condErr =
        assertThrows(
            BpmnException.class,
            () -> BpmnParser.parse(badCondXml.getBytes(StandardCharsets.UTF_8)));
    assertEquals("invalid_condition", condErr.code());

    String funcCallCondXml =
        """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="p">
            <startEvent id="s"/>
            <endEvent id="e"/>
            <sequenceFlow id="f1" sourceRef="s" targetRef="e">
              <conditionExpression>foo(1) == 1</conditionExpression>
            </sequenceFlow>
          </process>
        </definitions>
        """;
    BpmnException funcErr =
        assertThrows(
            BpmnException.class,
            () -> BpmnParser.parse(funcCallCondXml.getBytes(StandardCharsets.UTF_8)));
    assertEquals("invalid_condition", funcErr.code());

    // Unknown handler -> unknown_handler
    ProcessDefinition orderDef = BpmnParser.parse(readFixture("order.bpmn.xml"));
    ManualClock clock = new ManualClock(Instant.parse("2025-01-01T00:00:00Z"));
    Engine emptyEngine = new Engine(clock, Map.of(), null);
    BpmnException handlerErr =
        assertThrows(BpmnException.class, () -> emptyEngine.start(orderDef, Map.of("amount", 10)));
    assertEquals("unknown_handler", handlerErr.code());
  }

  @Test
  void conditionLanguageOperatorsAndAdversarialExpressions() {
    Map<String, Object> vars =
        Map.of("a", 10, "b", -5, "name", "alice", "flag", true, "and_var", 42, "not_var", "ok");

    assertTrue(
        ConditionEvaluator.evaluate(
            ConditionEvaluator.compile(
                "(a >= 10 and b == -5) and (name == 'alice' and not (flag == false))"),
            vars));
    assertTrue(
        ConditionEvaluator.evaluate(
            ConditionEvaluator.compile("a != 9 and a < 11 and a <= 10 and a > 9"), vars));
    assertTrue(
        ConditionEvaluator.evaluate(
            ConditionEvaluator.compile("and_var == 42 and not_var != 'no' or false"), vars));
    assertTrue(
        ConditionEvaluator.evaluate(
            ConditionEvaluator.compile(
                "${name < 'bob' and name <= 'alice' and name >= 'alice' and name > 'aaa'}"),
            vars));
    assertFalse(
        ConditionEvaluator.evaluate(ConditionEvaluator.compile("false and (a == 10)"), vars));
    assertTrue(ConditionEvaluator.evaluate(ConditionEvaluator.compile("true or (a == 999)"), vars));

    assertThrows(BpmnException.class, () -> ConditionEvaluator.compile("a == 'unterminated"));
    assertThrows(BpmnException.class, () -> ConditionEvaluator.compile("123abc == 1"));
    assertThrows(BpmnException.class, () -> ConditionEvaluator.compile("(a == 10"));
    assertThrows(BpmnException.class, () -> ConditionEvaluator.compile("a < b < 10"));
    assertThrows(BpmnException.class, () -> ConditionEvaluator.compile("-a == 10"));

    ConditionEvaluator.Expr nonBool = ConditionEvaluator.compile("a");
    assertThrows(BpmnException.class, () -> ConditionEvaluator.evaluate(nonBool, vars));
    ConditionEvaluator.Expr unknownVar = ConditionEvaluator.compile("missing == 1");
    assertThrows(BpmnException.class, () -> ConditionEvaluator.evaluate(unknownVar, vars));
  }

  @Test
  void textHelperAndExceptionMessageTruncation() {
    assertEquals("hello", Text.normalize("  Hello "));
    assertThrows(BlankInputException.class, () -> Text.normalize("   "));

    String longMsg = "x".repeat(250);
    BpmnException ex = new BpmnException("malformed", longMsg);
    assertEquals(200, ex.getMessage().length());
    assertEquals("malformed", ex.code());

    Instance s1 = new Instance("id-1", "COMPLETED", Map.of("k", 1), List.of(), List.of());
    Instance s2 = new Instance("id-2", "COMPLETED", Map.of("k", 1), List.of(), List.of());
    assertNotEquals(s1, s2);
    assertNotEquals(s1, "not-an-instance");
  }
}
