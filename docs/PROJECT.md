# Project record

## Objective

Token-based executor for a strict BPMN 2.0 subset with tasks, exclusive and parallel gateways and timer events, an injected clock and a replayable audit log.

## Scope

- In scope: BPMN 2.0 definitions, process, startEvent, endEvent, userTask, serviceTask, sequenceFlow, exclusiveGateway, parallelGateway, intermediateCatchEvent, boundaryEvent. Safe expression evaluation. Deterministic audit logging.
- Out of scope: Message/signal/error events, subprocesses, call activities, inclusive/event-based gateways, compensation, multi-instance, persistence (beyond the audit log), FEEL/JUEL expressions, graphical modeling.

## Timeline

| Date | Event |
|---|---|
| 2026 | Repository created from the factory utility template. |

## Effort metrics

| Metric | Value |
|---|---|
| Tokens (input / output) | 5928557 / 68689 |
| Hours (human) | pending measurement |
| Cost (USD) | 2.1804 |

## Factory run u927 (measured from API usage)

| Date (UTC) | Phase | Model | Batch | Input | Output | Cache write | Cache read | Cost (USD) |
|---|---|---|---|---|---|---|---|---|
| 2026-11-17 | generation | google/gemini-4-argon | yes | 7425 | 28801 | 0 | 0 | 0.6057 |
| 2026-11-17 | generation | google/gemini-4-argon | yes | 7453 | 32875 | 0 | 0 | 0.6873 |
| 2026-11-17 | fix-2 | google/gemini-3.8-flash | no | 228451 | 1898 | 0 | 2166520 | 0.3409 |
| 2026-11-17 | fix-3 | google/gemini-3.8-flash | no | 198261 | 1605 | 0 | 1921780 | 0.2988 |
| 2026-11-17 | audit | google/gemini-3.8-flash | no | 190631 | 2123 | 0 | 1185407 | 0.2398 |
| 2026-11-17 | docs | google/gemini-3.1-flash-lite | no | 22629 | 1387 | 0 | 0 | 0.0077 |

Total: $2.1804. Prices: vertex line, as measured by its adapter. Providers serve the current version of each model id, so the date tells which version answered; versions, launch stages and price sources per line are in the factory's model register (docs/MODELS.md).
