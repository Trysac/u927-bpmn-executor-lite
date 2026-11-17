# Testing

## Test Coverage

- `BpmnParserTest.java`: Tests parsing constraints, including `too_deep`, `input_too_large`, `malformed` (DOCTYPEs, invalid elements).
- `ConditionEvaluatorTest.java`: Tests the expression language syntax and semantic evaluation, including `invalid_condition` and operator precedence.
- `EngineTest.java`: Tests the execution engine, including step limits, token semantics, parallel joins, and timers (including `invalid_duration`).
- `InstanceTest.java`: Tests state transitions and audit log reconstruction via replay.

Tests are deterministic, utilize a configurable `Clock`, and verify outcomes by asserting against expected `AuditEvent` sequences and final instance states.
