package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.datadog.debugger.sink.ProbeStatusSink;
import datadog.trace.bootstrap.debugger.DebuggerSpan;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.CoreTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DebuggerTracerTest {

  private static final ProbeId SPAN_ID = new ProbeId("spanProbe-id", 1);

  @AfterEach
  public void after() {
    AgentTracer.forceRegister(null);
  }

  @Test
  public void createSpan() {
    AgentTracer.forceRegister(CoreTracer.builder().build());
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    DebuggerTracer debuggerTracer =
        new DebuggerTracer(DebuggerTracerTest::resolver, probeStatusSink);
    DebuggerSpan span =
        debuggerTracer.createSpan(SPAN_ID.getId(), "a-span", new String[] {"foo:bar"});
    AgentSpan underlyingSpan = ((DebuggerTracer.DebuggerSpanImpl) span).underlyingSpan;
    assertEquals("dd.dynamic.span", underlyingSpan.getSpanName());
    assertEquals("a-span", underlyingSpan.getResourceName());
    assertEquals(0, underlyingSpan.getDurationNano());
    assertEquals(
        "dd.dynamic.span",
        ((DebuggerTracer.DebuggerSpanImpl) span).currentScope.span().getSpanName());
    assertEquals(
        "a-span", ((DebuggerTracer.DebuggerSpanImpl) span).currentScope.span().getResourceName());
    span.finish();
    assertNotEquals(0, underlyingSpan.getDurationNano());
    verify(probeStatusSink).addEmitting(eq(SPAN_ID));
  }

  @Test
  public void setError() {
    AgentTracer.forceRegister(CoreTracer.builder().build());
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    DebuggerTracer debuggerTracer =
        new DebuggerTracer(DebuggerTracerTest::resolver, probeStatusSink);
    DebuggerSpan span =
        debuggerTracer.createSpan(SPAN_ID.getId(), "a-span", new String[] {"foo:bar"});
    span.setError(new IllegalArgumentException("oops"));
    AgentSpan underlyingSpan = ((DebuggerTracer.DebuggerSpanImpl) span).underlyingSpan;
    assertTrue(underlyingSpan.isError());
    span.finish();
    verify(probeStatusSink).addEmitting(eq(SPAN_ID));
  }

  @Test
  public void noApi() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    DebuggerTracer debuggerTracer =
        new DebuggerTracer(DebuggerTracerTest::resolver, probeStatusSink);
    DebuggerSpan span =
        debuggerTracer.createSpan(SPAN_ID.getId(), "a-span", new String[] {"foo:bar"});
    assertEquals(DebuggerSpan.NOOP_SPAN, span);
  }

  @Test
  public void badResolve() {
    AgentTracer.forceRegister(CoreTracer.builder().build());
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    DebuggerTracer debuggerTracer =
        new DebuggerTracer(DebuggerTracerTest::resolver, probeStatusSink);
    DebuggerSpan span = debuggerTracer.createSpan("badId", "a-span", new String[] {"foo:bar"});
    assertEquals(DebuggerSpan.NOOP_SPAN, span);
  }

  private static ProbeImplementation resolver(String probeId, Class<?> callingClass) {
    if (probeId.equals(SPAN_ID.getId())) {
      return new ProbeImplementation.NoopProbeImplementation(SPAN_ID, ProbeLocation.UNKNOWN);
    }
    return null;
  }
}
