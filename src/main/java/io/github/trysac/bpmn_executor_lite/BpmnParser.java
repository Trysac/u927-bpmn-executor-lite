package io.github.trysac.bpmn_executor_lite;

import io.github.trysac.bpmn_executor_lite.ProcessDefinition.FlowNode;
import io.github.trysac.bpmn_executor_lite.ProcessDefinition.NodeKind;
import io.github.trysac.bpmn_executor_lite.ProcessDefinition.SequenceFlow;
import io.github.trysac.bpmn_executor_lite.ProcessDefinition.TimerSpec;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Parser for the supported strict subset of BPMN 2.0 XML. */
public final class BpmnParser {

  /** Maximum allowed XML payload size in bytes (1 MiB). */
  public static final int MAX_BYTES = 1_048_576;

  /** Maximum allowed XML element nesting depth. */
  public static final int MAX_DEPTH = 64;

  /** Official BPMN 2.0 model XML namespace. */
  public static final String BPMN_NAMESPACE = "http://www.omg.org/spec/BPMN/20100524/MODEL";

  private static final Set<String> IGNORED_NAMESPACE_PREFIXES =
      Set.of(
          "http://www.omg.org/spec/BPMN/20100524/DI",
          "http://www.omg.org/spec/DD/20100524/DC",
          "http://www.omg.org/spec/DD/20100524/DI");

  private static final Set<String> IGNORED_LOCAL_NAMES =
      Set.of("documentation", "incoming", "outgoing", "extensionElements");

  private BpmnParser() {}

  /**
   * Parses a BPMN 2.0 XML byte array into a validated {@link ProcessDefinition}.
   *
   * @param xml raw UTF-8/XML bytes of the BPMN 2.0 document
   * @return the parsed {@link ProcessDefinition}
   * @throws NullPointerException if {@code xml} is null
   * @throws BpmnException if the XML exceeds size/depth limits, uses DOCTYPE, is malformed, or
   *     contains unsupported BPMN elements or invalid expressions/durations
   */
  public static ProcessDefinition parse(byte[] xml) {
    Objects.requireNonNull(xml, "xml");
    if (xml.length > MAX_BYTES) {
      throw new BpmnException(
          "input_too_large", "BPMN XML size " + xml.length + " exceeds limit " + MAX_BYTES);
    }
    if (xml.length == 0) {
      throw new BpmnException("malformed", "BPMN XML input is empty");
    }

    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);

    try {
      XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
      return parseStream(reader);
    } catch (BpmnException e) {
      throw e;
    } catch (XMLStreamException e) {
      throw mapXmlException(e);
    }
  }

  private static BpmnException mapXmlException(XMLStreamException e) {
    String msg = e.getMessage() != null ? e.getMessage() : "XML stream error";
    String lower = msg.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("doctype") || lower.contains("dtd")) {
      return new BpmnException("doctype_forbidden", "DOCTYPE declarations are forbidden", e);
    }
    return new BpmnException("malformed", "Malformed XML: " + msg, e);
  }

  private static ProcessDefinition parseStream(XMLStreamReader reader) throws XMLStreamException {
    int depth = 0;
    int ignoredSubtreeDepth = 0;
    boolean seenDefinitions = false;
    String processId = null;
    boolean insideProcess = false;

    Map<String, FlowNode> nodes = new LinkedHashMap<>();
    List<RawSequenceFlow> rawFlows = new ArrayList<>();

    Deque<ElementBuilder> stack = new ArrayDeque<>();

    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.DTD) {
        throw new BpmnException("doctype_forbidden", "DOCTYPE declarations are forbidden");
      }
      if (event == XMLStreamConstants.ENTITY_REFERENCE) {
        throw new BpmnException("malformed", "Entity references are forbidden");
      }

      if (event == XMLStreamConstants.START_ELEMENT) {
        depth++;
        if (depth > MAX_DEPTH) {
          throw new BpmnException("too_deep", "XML nesting depth exceeds maximum of " + MAX_DEPTH);
        }

        if (ignoredSubtreeDepth > 0) {
          ignoredSubtreeDepth++;
          continue;
        }

        String ns = reader.getNamespaceURI();
        String local = reader.getLocalName();

        if (isIgnoredElement(ns, local)) {
          ignoredSubtreeDepth = 1;
          continue;
        }

        if (ns != null && !ns.isEmpty() && !BPMN_NAMESPACE.equals(ns)) {
          throw new BpmnException("unsupported_element", "Unsupported element: " + local);
        }

        if ("definitions".equals(local)) {
          if (depth != 1) {
            throw new BpmnException("malformed", "definitions must be the root element");
          }
          seenDefinitions = true;
          continue;
        }

        if (!seenDefinitions) {
          throw new BpmnException("malformed", "Root element must be definitions, found: " + local);
        }

        if ("process".equals(local)) {
          if (insideProcess || processId != null) {
            throw new BpmnException("malformed", "Only a single process element is supported");
          }
          String id = reader.getAttributeValue(null, "id");
          processId = (id == null || id.isBlank()) ? "process" : id;
          insideProcess = true;
          continue;
        }

        if (!insideProcess) {
          throw new BpmnException("unsupported_element", "Unsupported top-level element: " + local);
        }

        handleProcessElementStart(reader, local, stack, nodes);
      } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
        if (ignoredSubtreeDepth == 0 && !stack.isEmpty()) {
          ElementBuilder top = stack.peek();
          if (top.captureText) {
            top.text.append(reader.getText());
          }
        }
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        if (ignoredSubtreeDepth > 0) {
          ignoredSubtreeDepth--;
          depth--;
          continue;
        }

        String local = reader.getLocalName();
        if ("process".equals(local) && insideProcess && stack.isEmpty()) {
          insideProcess = false;
        } else if (!"definitions".equals(local) && insideProcess) {
          handleProcessElementEnd(local, stack, nodes, rawFlows);
        }
        depth--;
      }
    }

    if (!seenDefinitions || processId == null) {
      throw new BpmnException("malformed", "Missing BPMN definitions or process element");
    }

    return buildProcessDefinition(processId, nodes, rawFlows);
  }

  private static boolean isIgnoredElement(String ns, String local) {
    if (ns != null && IGNORED_NAMESPACE_PREFIXES.contains(ns)) {
      return true;
    }
    if (local.startsWith("BPMN")) {
      return true;
    }
    return IGNORED_LOCAL_NAMES.contains(local);
  }

  private static void handleProcessElementStart(
      XMLStreamReader reader,
      String local,
      Deque<ElementBuilder> stack,
      Map<String, FlowNode> nodes) {
    if (stack.isEmpty()) {
      switch (local) {
        case "startEvent" -> stack.push(startNode(reader, NodeKind.START_EVENT, nodes));
        case "endEvent" -> stack.push(startNode(reader, NodeKind.END_EVENT, nodes));
        case "userTask" -> stack.push(startNode(reader, NodeKind.USER_TASK, nodes));
        case "serviceTask" -> stack.push(startNode(reader, NodeKind.SERVICE_TASK, nodes));
        case "exclusiveGateway" -> {
          ElementBuilder b = startNode(reader, NodeKind.EXCLUSIVE_GATEWAY, nodes);
          b.defaultFlowId = reader.getAttributeValue(null, "default");
          stack.push(b);
        }
        case "parallelGateway" -> stack.push(startNode(reader, NodeKind.PARALLEL_GATEWAY, nodes));
        case "intermediateCatchEvent" ->
            stack.push(startNode(reader, NodeKind.INTERMEDIATE_CATCH_EVENT, nodes));
        case "boundaryEvent" -> {
          ElementBuilder b = startNode(reader, NodeKind.BOUNDARY_EVENT, nodes);
          b.attachedToRef = reader.getAttributeValue(null, "attachedToRef");
          if (b.attachedToRef == null || b.attachedToRef.isBlank()) {
            throw new BpmnException("malformed", "boundaryEvent missing attachedToRef");
          }
          String cancelAttr = reader.getAttributeValue(null, "cancelActivity");
          if (cancelAttr == null || cancelAttr.isBlank() || "true".equals(cancelAttr)) {
            b.cancelActivity = true;
          } else if ("false".equals(cancelAttr)) {
            b.cancelActivity = false;
          } else {
            throw new BpmnException("malformed", "Invalid cancelActivity attribute: " + cancelAttr);
          }
          stack.push(b);
        }
        case "sequenceFlow" -> {
          String id = requireId(reader, "sequenceFlow", nodes);
          String sourceRef = reader.getAttributeValue(null, "sourceRef");
          String targetRef = reader.getAttributeValue(null, "targetRef");
          if (sourceRef == null
              || sourceRef.isBlank()
              || targetRef == null
              || targetRef.isBlank()) {
            throw new BpmnException(
                "malformed", "sequenceFlow '" + id + "' requires sourceRef and targetRef");
          }
          ElementBuilder b = new ElementBuilder("sequenceFlow", id);
          b.sourceRef = sourceRef;
          b.targetRef = targetRef;
          stack.push(b);
        }
        default -> throw new BpmnException("unsupported_element", "Unsupported element: " + local);
      }
      return;
    }

    ElementBuilder parent = stack.peek();
    if ("sequenceFlow".equals(parent.elementName) && "conditionExpression".equals(local)) {
      ElementBuilder cond = new ElementBuilder("conditionExpression", null);
      cond.captureText = true;
      stack.push(cond);
    } else if (isTimerCapable(parent.kind) && "timerEventDefinition".equals(local)) {
      parent.hasTimerDefinition = true;
      stack.push(new ElementBuilder("timerEventDefinition", null));
    } else if (parent.kind == NodeKind.END_EVENT && "terminateEventDefinition".equals(local)) {
      parent.terminateEnd = true;
      stack.push(new ElementBuilder("terminateEventDefinition", null));
    } else if ("timerEventDefinition".equals(parent.elementName)
        && ("timeDuration".equals(local) || "timeDate".equals(local))) {
      ElementBuilder timeChild = new ElementBuilder(local, null);
      timeChild.captureText = true;
      stack.push(timeChild);
    } else {
      throw new BpmnException("unsupported_element", "Unsupported element: " + local);
    }
  }

  private static boolean isTimerCapable(NodeKind kind) {
    return kind == NodeKind.START_EVENT
        || kind == NodeKind.INTERMEDIATE_CATCH_EVENT
        || kind == NodeKind.BOUNDARY_EVENT;
  }

  private static ElementBuilder startNode(
      XMLStreamReader reader, NodeKind kind, Map<String, FlowNode> nodes) {
    String id = requireId(reader, kind.name(), nodes);
    ElementBuilder b = new ElementBuilder(kind.name(), id);
    b.kind = kind;
    return b;
  }

  private static String requireId(
      XMLStreamReader reader, String elementName, Map<String, FlowNode> nodes) {
    String id = reader.getAttributeValue(null, "id");
    if (id == null || id.isBlank()) {
      throw new BpmnException("malformed", "Missing id on " + elementName);
    }
    if (nodes.containsKey(id)) {
      throw new BpmnException("malformed", "Duplicate element id: " + id);
    }
    return id;
  }

  private static void handleProcessElementEnd(
      String local,
      Deque<ElementBuilder> stack,
      Map<String, FlowNode> nodes,
      List<RawSequenceFlow> rawFlows) {
    if (stack.isEmpty()) {
      return;
    }
    ElementBuilder finished = stack.pop();
    if ("conditionExpression".equals(local) && !stack.isEmpty()) {
      stack.peek().conditionText = finished.text.toString().strip();
      return;
    }
    if ("timeDuration".equals(local) && !stack.isEmpty()) {
      ElementBuilder timerDef = stack.peek();
      timerDef.timeDurationText = finished.text.toString().strip();
      return;
    }
    if ("timeDate".equals(local) && !stack.isEmpty()) {
      ElementBuilder timerDef = stack.peek();
      timerDef.timeDateText = finished.text.toString().strip();
      return;
    }
    if ("timerEventDefinition".equals(local) && !stack.isEmpty()) {
      ElementBuilder eventNode = stack.peek();
      eventNode.timerSpec = parseTimerSpec(finished.timeDurationText, finished.timeDateText);
      return;
    }
    if ("terminateEventDefinition".equals(local)) {
      return;
    }

    if ("sequenceFlow".equals(finished.elementName)) {
      rawFlows.add(
          new RawSequenceFlow(
              finished.id, finished.sourceRef, finished.targetRef, finished.conditionText));
      return;
    }

    if (finished.kind != null) {
      if ((finished.kind == NodeKind.INTERMEDIATE_CATCH_EVENT
              || finished.kind == NodeKind.BOUNDARY_EVENT)
          && (!finished.hasTimerDefinition || finished.timerSpec == null)) {
        throw new BpmnException(
            "malformed", finished.kind + " '" + finished.id + "' requires a timerEventDefinition");
      }
      FlowNode node =
          new FlowNode(
              finished.id,
              finished.kind,
              finished.defaultFlowId,
              finished.terminateEnd,
              finished.timerSpec,
              finished.attachedToRef,
              finished.cancelActivity);
      nodes.put(node.id(), node);
    }
  }

  private static TimerSpec parseTimerSpec(String durationText, String dateText) {
    if (durationText != null && !durationText.isEmpty()) {
      try {
        Duration d = Duration.parse(durationText);
        return new TimerSpec(d, null);
      } catch (DateTimeParseException e) {
        throw new BpmnException(
            "invalid_duration", "Invalid ISO-8601 duration: " + durationText, e);
      }
    }
    if (dateText != null && !dateText.isEmpty()) {
      try {
        Instant instant = Instant.parse(dateText);
        return new TimerSpec(null, instant);
      } catch (DateTimeParseException e) {
        throw new BpmnException("invalid_duration", "Invalid ISO-8601 date: " + dateText, e);
      }
    }
    throw new BpmnException(
        "invalid_duration", "timerEventDefinition requires timeDuration or timeDate");
  }

  private static ProcessDefinition buildProcessDefinition(
      String processId, Map<String, FlowNode> nodes, List<RawSequenceFlow> rawFlows) {
    List<FlowNode> startEvents = new ArrayList<>();
    Map<String, List<FlowNode>> boundaryByAttached = new LinkedHashMap<>();

    for (FlowNode node : nodes.values()) {
      if (node.kind() == NodeKind.START_EVENT) {
        startEvents.add(node);
      } else if (node.kind() == NodeKind.BOUNDARY_EVENT) {
        FlowNode target = nodes.get(node.attachedToRef());
        if (target == null
            || (target.kind() != NodeKind.USER_TASK && target.kind() != NodeKind.SERVICE_TASK)) {
          throw new BpmnException(
              "malformed",
              "boundaryEvent '"
                  + node.id()
                  + "' attached to invalid target: "
                  + node.attachedToRef());
        }
        boundaryByAttached.computeIfAbsent(node.attachedToRef(), k -> new ArrayList<>()).add(node);
      }
    }

    if (startEvents.isEmpty()) {
      throw new BpmnException("malformed", "Process must contain at least one startEvent");
    }
    if (startEvents.size() > 1) {
      long noneStarts = startEvents.stream().filter(s -> s.timer() == null).count();
      if (noneStarts == startEvents.size()) {
        throw new BpmnException("malformed", "Process cannot have multiple non-timer start events");
      }
    }

    Map<String, SequenceFlow> sequenceFlows = new LinkedHashMap<>();
    Map<String, List<SequenceFlow>> outgoingBySource = new LinkedHashMap<>();
    Map<String, List<SequenceFlow>> incomingByTarget = new LinkedHashMap<>();

    for (RawSequenceFlow raw : rawFlows) {
      if (sequenceFlows.containsKey(raw.id) || nodes.containsKey(raw.id)) {
        throw new BpmnException("malformed", "Duplicate sequenceFlow id: " + raw.id);
      }
      if (!nodes.containsKey(raw.sourceRef)) {
        throw new BpmnException(
            "malformed",
            "sequenceFlow '" + raw.id + "' references unknown source: " + raw.sourceRef);
      }
      if (!nodes.containsKey(raw.targetRef)) {
        throw new BpmnException(
            "malformed",
            "sequenceFlow '" + raw.id + "' references unknown target: " + raw.targetRef);
      }
      ConditionEvaluator.Expr compiled =
          raw.conditionText != null ? ConditionEvaluator.compile(raw.conditionText) : null;
      SequenceFlow sf =
          new SequenceFlow(raw.id, raw.sourceRef, raw.targetRef, raw.conditionText, compiled);
      sequenceFlows.put(sf.id(), sf);
      outgoingBySource.computeIfAbsent(sf.sourceRef(), k -> new ArrayList<>()).add(sf);
      incomingByTarget.computeIfAbsent(sf.targetRef(), k -> new ArrayList<>()).add(sf);
    }

    for (FlowNode node : nodes.values()) {
      if (node.kind() == NodeKind.EXCLUSIVE_GATEWAY && node.defaultFlowId() != null) {
        SequenceFlow def = sequenceFlows.get(node.defaultFlowId());
        if (def == null || !def.sourceRef().equals(node.id())) {
          throw new BpmnException(
              "malformed",
              "exclusiveGateway '"
                  + node.id()
                  + "' references invalid default flow: "
                  + node.defaultFlowId());
        }
      }
    }

    return new ProcessDefinition(
        processId,
        nodes,
        sequenceFlows,
        startEvents,
        outgoingBySource,
        incomingByTarget,
        boundaryByAttached);
  }

  private record RawSequenceFlow(
      String id, String sourceRef, String targetRef, String conditionText) {}

  private static final class ElementBuilder {
    final String elementName;
    final String id;
    NodeKind kind;
    String defaultFlowId;
    boolean terminateEnd;
    boolean hasTimerDefinition;
    TimerSpec timerSpec;
    String attachedToRef;
    boolean cancelActivity = true;
    String sourceRef;
    String targetRef;
    String conditionText;
    String timeDurationText;
    String timeDateText;
    boolean captureText;
    final StringBuilder text = new StringBuilder();

    ElementBuilder(String elementName, String id) {
      this.elementName = elementName;
      this.id = id;
    }
  }
}
