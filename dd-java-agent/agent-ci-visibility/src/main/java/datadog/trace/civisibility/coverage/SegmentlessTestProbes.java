package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.CoverageErrorType;
import datadog.trace.api.civisibility.telemetry.tag.Library;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SegmentlessTestProbes implements CoverageProbeStore {

  private static final Logger log = LoggerFactory.getLogger(SegmentlessTestProbes.class);

  // test starts and finishes in the same thread,
  // and in this thread we do not need to synchronize access
  private final Thread testThread = Thread.currentThread();
  // confined to testThread
  private boolean started;
  private volatile Class<?> lastCoveredClass;
  private final Map<Class<?>, Class<?>> coveredClasses;
  private final Map<Thread, Map<Class<?>, Class<?>>> concurrentCoveredClasses;
  private final Collection<String> nonCodeResources;
  private final SourcePathResolver sourcePathResolver;
  private final CiVisibilityMetricCollector metricCollector;
  private volatile TestReport testReport;

  SegmentlessTestProbes(
      SourcePathResolver sourcePathResolver, CiVisibilityMetricCollector metricCollector) {
    this.sourcePathResolver = sourcePathResolver;
    this.metricCollector = metricCollector;
    coveredClasses = new IdentityHashMap<>();
    concurrentCoveredClasses = new ConcurrentHashMap<>();
    nonCodeResources = new ConcurrentLinkedQueue<>();
  }

  @Override
  public void record(Class<?> clazz, long classId, int probeId) {
    record(clazz);
  }

  @Override
  public void record(Class<?> clazz) {
    try {
      if (clazz != lastCoveredClass) {
        Thread currentThread = Thread.currentThread();
        if (currentThread == testThread) {
          coveredClasses.put(lastCoveredClass, null);
          lastCoveredClass = clazz;

          if (!started) {
            started = true;
            metricCollector.add(CiVisibilityCountMetric.CODE_COVERAGE_STARTED, 1, Library.CUSTOM);
          }
        } else {
          concurrentCoveredClasses
              .computeIfAbsent(currentThread, t -> new IdentityHashMap<>())
              .put(clazz, null);
        }
      }

    } catch (Exception e) {
      metricCollector.add(
          CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.RECORD);
      throw e;
    }
  }

  @Override
  public void recordNonCodeResource(String absolutePath) {
    nonCodeResources.add(absolutePath);
  }

  @Override
  public boolean report(Long testSessionId, Long testSuiteId, long spanId) {
    try {
      Map<Class<?>, Class<?>> classes =
          map(coveredClasses.size() + concurrentCoveredClasses.size());
      classes.putAll(coveredClasses);

      for (Map<Class<?>, Class<?>> threadCoveredClasses : concurrentCoveredClasses.values()) {
        classes.putAll(threadCoveredClasses);
      }

      classes.put(lastCoveredClass, null);
      classes.remove(null);

      if (classes.isEmpty() && nonCodeResources.isEmpty()) {
        return false;
      }

      Set<String> coveredPaths = set(coveredClasses.size() + nonCodeResources.size());
      for (Class<?> clazz : classes.keySet()) {
        String sourcePath = sourcePathResolver.getSourcePath(clazz);
        if (sourcePath == null) {
          log.debug(
              "Skipping coverage reporting for {} because source path could not be determined",
              clazz);
          metricCollector.add(
              CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.PATH);
          continue;
        }
        coveredPaths.add(sourcePath);
      }

      for (String nonCodeResource : nonCodeResources) {
        String resourcePath = sourcePathResolver.getResourcePath(nonCodeResource);
        if (resourcePath == null) {
          log.debug(
              "Skipping coverage reporting for {} because resource path could not be determined",
              nonCodeResource);
          metricCollector.add(
              CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.PATH);
          continue;
        }
        coveredPaths.add(resourcePath);
      }

      List<TestReportFileEntry> fileEntries = new ArrayList<>(coveredPaths.size());
      for (String path : coveredPaths) {
        TestReportFileEntry fileEntry = new TestReportFileEntry(path, Collections.emptyList());
        fileEntries.add(fileEntry);
      }

      testReport = new TestReport(testSessionId, testSuiteId, spanId, fileEntries);
      metricCollector.add(CiVisibilityCountMetric.CODE_COVERAGE_FINISHED, 1, Library.CUSTOM);
      metricCollector.add(
          CiVisibilityDistributionMetric.CODE_COVERAGE_FILES,
          testReport.getTestReportFileEntries().size());
      return testReport.isNotEmpty();

    } catch (Exception e) {
      metricCollector.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1);
      throw e;
    }
  }

  private static <K, V> Map<K, V> map(int size) {
    return new IdentityHashMap<>(Math.max((int) (size / .75f) + 1, 16));
  }

  private static <T> Set<T> set(int size) {
    return new HashSet<>(Math.max((int) (size / .75f) + 1, 16));
  }

  @Nullable
  @Override
  public TestReport getReport() {
    return testReport;
  }

  public static class SegmentlessTestProbesFactory implements CoverageProbeStoreFactory {

    private final CiVisibilityMetricCollector metricCollector;

    public SegmentlessTestProbesFactory(CiVisibilityMetricCollector metricCollector) {
      this.metricCollector = metricCollector;
    }

    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {
      // ignore
    }

    @Override
    public CoverageProbeStore create(SourcePathResolver sourcePathResolver) {
      return new SegmentlessTestProbes(sourcePathResolver, metricCollector);
    }
  }
}
