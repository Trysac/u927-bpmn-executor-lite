# StAX Parser, Hand-Written Condition AST, and Token-Queue Execution Engine

Status: accepted (generated, pending human review)

## Context

Teams need a lightweight, embeddable BPMN 2.0 executor for approval and order workflows without external dependencies, reflection/scripting vulnerabilities, or a relational database.

## Decision

Use javax.xml.stream (StAX) with DTDs disabled to parse the strict BPMN 2.0 subset into an immutable ProcessDefinition, compile sequenceFlow conditions into a safe hand-written AST, and execute processes via a deterministic token queue backed by an injected Clock and replayable AuditEvent log.

## Consequences

Zero external runtime dependencies, immunity to XXE and expression injection, deterministic unit testing of timers via manual clocks, and explicit failure on unsupported BPMN elements.
