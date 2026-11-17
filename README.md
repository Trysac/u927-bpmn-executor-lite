# bpmn-executor-lite

Token-based executor for a strict BPMN 2.0 subset with tasks, exclusive and parallel gateways and timer events, an injected clock and a replayable audit log.

## Why bpmn-executor-lite?

Teams that model approvals and order flows in BPMN either embed a full engine such as Camunda or Flowable, with a database and a large runtime, or rewrite the diagram as code that drifts from it. Many processes only use tasks, gateways and timers. This library reads BPMN 2.0 XML in that subset, executes it with token semantics and an injected clock, and refuses any element it does not implement instead of ignoring it.

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
  <groupId>io.github.trysac</groupId>
  <artifactId>bpmn-executor-lite</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Usage

```java
ProcessDefinition def = BpmnParser.parse(xmlBytes);
Engine engine = new Engine(Instant::now, Map.of(
    "check", (id, vars) -> Map.of(),
    "ship", (id, vars) -> Map.of("shipped", true)
), event -> {});
Instance instance = engine.start(def, Map.of("amount", 150));
engine.complete(instance, "approve", Map.of("approved", true));
```

## API Summary

- `BpmnParser.parse(byte[] xml)`: Parses XML up to 1 MiB. Throws `BpmnException` for malformed XML, `too_deep` (nesting > 64), `input_too_large`, or `unsupported_element`.
- `Engine(Clock, Map<String, ServiceHandler>, Consumer<AuditEvent>)`: The execution engine.
- `Engine.start(ProcessDefinition, Map<String, Object>)`: Starts a process instance.
- `Engine.complete(Instance, String, Map<String, Object>)`: Completes a `userTask`. Throws `unknown_task` if invalid.
- `Engine.tick()`: Advances timers.
- `Engine.replay(ProcessDefinition, List<AuditEvent>)`: Reconstructs state from an audit log.
- `AuditEvent` and `Instance`: Immutable record representations of state and audit logs.

## Why not Camunda or Flowable?

This library is an embeddable, database-free executor whose refusal of unsupported elements is explicit. It is designed for simple process automation where full engine features (persistence, UI, etc.) are unnecessary complexity.

## Security

See [docs/SECURITY.md](docs/SECURITY.md).
