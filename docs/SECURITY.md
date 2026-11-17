# Security & Threat Model

## Threat Model

`bpmn-executor-lite` parses untrusted BPMN 2.0 XML documents and evaluates embedded condition expressions.

### Defended

- **XML External Entities (XXE)**: DTDs and external entities are disabled at the parser level.
- **Resource Exhaustion**: XML input limited to 1 MiB, nesting depth to 64, and execution steps to 10,000 per call.
- **Insecure Code Execution**: Expression language is limited to simple logic (no method calls, no reflection, no property access).
- **Sensitive Data Leakage**: Audit log only stores metadata (element IDs, timestamps, kinds), never process variables.

### Not Defended

- Maliciously constructed XML payloads that fit within the size/depth limits but cause high computational overhead (e.g., complex condition evaluations).
- The provided `ServiceHandler` implementations; they run in the same JVM and have access to the instance's state.
