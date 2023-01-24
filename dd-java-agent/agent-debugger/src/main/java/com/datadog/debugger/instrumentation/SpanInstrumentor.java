package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_SPAN_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;

import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.Where;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public class SpanInstrumentor extends Instrumentor {
  private String spanName;
  private int spanVar;

  public SpanInstrumentor(
      SpanProbe spanProbe,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics) {
    super(spanProbe, classLoader, classNode, methodNode, diagnostics);
    this.spanName = spanProbe.getName();
  }

  public void instrument() {
    if (isLineProbe) {
      fillLineMap();
      addRangeSpan(lineMap);
    } else {
      spanVar = newVar(DEBUGGER_SPAN_TYPE);
      processInstructions();
      LabelNode initSpanLabel = new LabelNode();
      InsnList insnList = createSpan(initSpanLabel);
      LabelNode endLabel = new LabelNode();
      methodNode.instructions.insert(methodNode.instructions.getLast(), endLabel);

      LabelNode handlerLabel = new LabelNode();
      InsnList handler = createCatchHandler(handlerLabel);
      methodNode.instructions.add(handler);
      methodNode.tryCatchBlocks.add(
          new TryCatchBlockNode(initSpanLabel, endLabel, handlerLabel, null));
      methodNode.instructions.insert(methodEnterLabel, insnList);
    }
  }

  private InsnList createCatchHandler(LabelNode handlerLabel) {
    InsnList handler = new InsnList();
    handler.add(handlerLabel);
    // stack [exception]
    handler.add(new InsnNode(Opcodes.DUP));
    // stack [exception, exception]
    handler.add(new VarInsnNode(Opcodes.ALOAD, spanVar));
    // stack [exception, exception, span]
    handler.add(new InsnNode(Opcodes.SWAP));
    // stack [exception, span, exception]
    invokeInterface(
        handler, DEBUGGER_SPAN_TYPE, "setError", Type.VOID_TYPE, Type.getType(Throwable.class));
    // stack [exception]
    debuggerSpanFinish(handler);
    handler.add(new InsnNode(Opcodes.ATHROW));
    return handler;
  }

  private InsnList createSpan(LabelNode initSpanLabel) {
    InsnList insnList = new InsnList();
    ldc(insnList, spanName); // stack: [string]
    pushTags(insnList, definition.getTags()); // stack: [string, tags]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "createSpan",
        DEBUGGER_SPAN_TYPE,
        STRING_TYPE,
        Types.asArray(STRING_TYPE, 1)); // tags
    // stack: [span]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, spanVar)); // stack: []
    insnList.add(initSpanLabel);
    return insnList;
  }

  private void addRangeSpan(LineMap lineMap) {
    Where.SourceLine[] targetLines = definition.getWhere().getSourceLines();
    if (targetLines == null || targetLines.length == 0) {
      // no line capture to perform
      return;
    }
    if (lineMap.isEmpty()) {
      reportError("Missing line debug information.");
      return;
    }
    for (Where.SourceLine sourceLine : targetLines) {
      int from = sourceLine.getFrom();
      int till = sourceLine.getTill();
      LabelNode beforeLabel = lineMap.getLineLabel(from);
      LabelNode afterLabel = lineMap.getLineLabel(till);
      if (beforeLabel == null || afterLabel == null) {
        reportError(
            "No line info for " + (sourceLine.isSingleLine() ? "line " : "range ") + sourceLine);
        return;
      }
      spanVar = newVar(DEBUGGER_SPAN_TYPE);
      LabelNode initSpanLabel = new LabelNode();
      InsnList createSpaninsnList = createSpan(initSpanLabel);
      methodNode.instructions.insertBefore(beforeLabel.getNext(), createSpaninsnList);
      LabelNode handlerLabel = new LabelNode();
      InsnList handler = createCatchHandler(handlerLabel);
      methodNode.instructions.add(handler);
      methodNode.tryCatchBlocks.add(
          new TryCatchBlockNode(initSpanLabel, afterLabel, handlerLabel, null));
      InsnList finishSpanInsnList = new InsnList();
      debuggerSpanFinish(finishSpanInsnList);
      methodNode.instructions.insert(afterLabel, finishSpanInsnList);
    }
  }

  @Override
  protected InsnList getReturnHandlerInsnList() {
    InsnList insnList = new InsnList();
    debuggerSpanFinish(insnList);
    return insnList;
  }

  private void debuggerSpanFinish(InsnList insnList) {
    insnList.add(new VarInsnNode(Opcodes.ALOAD, spanVar));
    invokeInterface(insnList, DEBUGGER_SPAN_TYPE, "finish", Type.VOID_TYPE);
  }
}