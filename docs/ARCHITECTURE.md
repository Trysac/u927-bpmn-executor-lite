# Architecture

`bpmn-executor-lite` is structured into five cohesive components in package `io.github.trysac.bpmn_executor_lite`:

1. **Parser (`BpmnParser` & `ProcessDefinition`)**:
   - Uses Java's standard `javax.xml.stream.XMLStreamReader` configured with `SUPPORT_DTD = false` and external entities disabled.
   - Enforces a 1 MiB (`1_048_576` bytes) input size limit (`input_too_large`) and a 64-level XML nesting limit (`too_deep`).
   - Rejects any BPMN element outside the supported strict subset with `unsupported_element`.
   - Pre-compiles all `conditionExpression` strings and validates ISO-8601 `timeDuration` / `timeDate` timer specs (`invalid_duration`) at parse time.

2. **Condition Evaluator (`ConditionEvaluator`)**:
   - Hand-written lexer, recursive-descent parser, and AST evaluator.
   - Supports variable identifiers, single-quoted strings, signed/unsigned integers, booleans, `==`, `!=`, `<`, `<=`, `>`, `>=`, `and`, `or`, `not`, and parentheses.
   - Forbids method calls and property access (`invalid_condition`).

3. **Token Engine (`Engine`)**:
   - Executes tokens deterministically across nodes using a FIFO queue.
   - Enforces a 10,000-step limit (`step_limit`) per `start`, `complete`, or `tick` invocation to prevent infinite loops.
   - Evaluates due timers against the injected `Clock`.
   - Emits `AuditEvent` records (without variable payloads) and supports deterministic state reconstruction via `Engine.replay`.
