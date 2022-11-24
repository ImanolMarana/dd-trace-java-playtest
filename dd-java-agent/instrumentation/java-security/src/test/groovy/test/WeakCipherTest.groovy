package test

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge
import foo.bar.TestSuite

import javax.crypto.Cipher
import java.security.NoSuchAlgorithmException
import java.security.Provider

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class WeakCipherTest extends AgentTestRunner {

  def "unavailable cipher algorithm"() {

    when:
    runUnderTrace("WeakHashingRootSpan") {
      new TestSuite().getCipherInstance("SHA-XXX")
    }

    then:
    thrown NoSuchAlgorithmException
  }

  def "test weak cipher instrumentation"() {
    setup:
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new TestSuite().getCipherInstance("DES")

    then:
    1 * module.onCipherAlgorithm(_)
  }

  def "test weak cipher instrumentation with provider"() {
    setup:
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)
    final provider = providerFor('DES')

    when:
    new TestSuite().getCipherInstance("DES", provider)

    then:
    1 * module.onCipherAlgorithm(_)
  }

  def "test weak cipher instrumentation with provider string"() {
    setup:
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)
    final provider = providerFor('DES')

    when:
    new TestSuite().getCipherInstance('DES', provider.getName())

    then:
    1 * module.onCipherAlgorithm(_)
  }

  def "weak cipher instrumentation with null argument"() {
    setup:
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new TestSuite().getCipherInstance(null)

    then:
    thrown NoSuchAlgorithmException
  }

  private static Provider providerFor(final String algo) {
    final instance = Cipher.getInstance(algo)
    return instance.getProvider()
  }
}
