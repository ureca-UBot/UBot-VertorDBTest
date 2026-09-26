package com.myapp.benchmark;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceCollectorTest {
    @Test
    @SuppressWarnings("unchecked")
    void runningSampleCountUsesTheSameWindowAsFinalUsageWithoutStoppingTheSampler() {
        try (var measurement = new ResourceCollector().start(List.of())) {
            var snapshots = (List<ResourceCollector.TimedSnapshot>) ReflectionTestUtils.getField(measurement, "snapshots");
            var sample = new ResourceCollector.Snapshot(80, 200, 1000);
            snapshots.addAll(List.of(
                    new ResourceCollector.TimedSnapshot(sample, 90, 110),
                    new ResourceCollector.TimedSnapshot(sample, 110, 130),
                    new ResourceCollector.TimedSnapshot(sample, 150, 170),
                    new ResourceCollector.TimedSnapshot(sample, 190, 210)));
            assertThat(measurement.sampleCount(100, 160)).isEqualTo(1);
            assertThat(measurement.sampleCount(100, 200)).isEqualTo(2);
            var sampler = (ScheduledExecutorService) ReflectionTestUtils.getField(measurement, "executor");
            assertThat(sampler.isShutdown()).isFalse();
            assertThat(measurement.usage(100, 200).samples()).isEqualTo(2);
            assertThat(sampler.isShutdown()).isTrue();
        }
    }

    @Test
    void excludesCapturesOverlappingWarmupOrPostMeasurementIdleTime() {
        var idle = new ResourceCollector.Snapshot(0.01, 100, 1000);
        var busy = new ResourceCollector.Snapshot(180, 200, 2000);
        var measured = ResourceCollector.Measurement.withinWindow(List.of(
                new ResourceCollector.TimedSnapshot(idle, 90, 110),
                new ResourceCollector.TimedSnapshot(busy, 110, 190),
                new ResourceCollector.TimedSnapshot(idle, 190, 210)), 100, 200);
        assertThat(measured).containsExactly(busy);
        var usage = ResourceCollector.Measurement.summarize(idle, measured);
        assertThat(usage.averageCpuPercent()).isEqualTo(180);
        assertThat(usage.samples()).isEqualTo(1);
    }

    @Test
    void parsesDockerDecimalAndBinaryUnits() {
        assertThat(ResourceCollector.Measurement.parseBytes("12.5MB")).isEqualTo(12_500_000L);
        assertThat(ResourceCollector.Measurement.parseBytes("2MiB")).isEqualTo(2_097_152L);
        assertThat(ResourceCollector.Measurement.parseBytes("1.5GB")).isEqualTo(1_500_000_000L);
    }

    @Test
    void excludesIdleBaselineFromCpuAndUsesItForDiskDelta() {
        ResourceCollector.Snapshot baseline = new ResourceCollector.Snapshot(1, 100, 1_000);
        ResourceCollector.Usage usage = ResourceCollector.Measurement.summarize(baseline, List.of(
                new ResourceCollector.Snapshot(80, 200, 1_500),
                new ResourceCollector.Snapshot(120, 250, 2_200)));

        assertThat(usage.averageCpuPercent()).isEqualTo(100);
        assertThat(usage.peakCpuPercent()).isEqualTo(120);
        assertThat(usage.averageMemoryBytes()).isEqualTo(225);
        assertThat(usage.peakMemoryBytes()).isEqualTo(250);
        assertThat(usage.diskWriteBytes()).isEqualTo(1_200);
    }
}
