package com.myapp.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Samples Docker container resources without adding the sampling cost to search latency. */
public class ResourceCollector {

    public Measurement start(String containerName) {
        return start(containerName == null || containerName.isBlank() ? List.of() : List.of(containerName));
    }

    public Measurement start(List<String> containerNames) {
        Measurement measurement = new Measurement(containerNames);
        measurement.start();
        return measurement;
    }

    public static final class Measurement implements AutoCloseable {
        private final List<String> containerNames;
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "docker-resource-sampler");
            thread.setDaemon(true);
            return thread;
        });
        private final List<TimedSnapshot> snapshots = new ArrayList<>();
        private Snapshot baseline;

        private Measurement(List<String> containerNames) {
            this.containerNames = containerNames == null
                    ? List.of()
                    : containerNames.stream().filter(name -> name != null && !name.isBlank()).toList();
        }

        private void start() {
            if (containerNames.isEmpty()) return;
            // Keep the pre-run snapshot only as the Block I/O baseline. Including its idle CPU value
            // would severely under-report short benchmarks that finish before the first scheduled poll.
            baseline = captureSafely();
            executor.scheduleWithFixedDelay(this::sampleSafely, 500, 500, TimeUnit.MILLISECONDS);
        }

        private void sampleSafely() {
            long started = System.nanoTime();
            Snapshot snapshot = captureSafely();
            long ended = System.nanoTime();
            if (snapshot == null) return;
            synchronized (snapshots) {
                snapshots.add(new TimedSnapshot(snapshot, started, ended));
            }
        }

        private Snapshot captureSafely() {
            Process process = null;
            try {
                List<String> command = new ArrayList<>(List.of(
                        "docker", "stats", "--no-stream", "--format",
                        "{{.CPUPerc}}|{{.MemUsage}}|{{.BlockIO}}"));
                command.addAll(containerNames);
                process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    process.destroyForcibly();
                    return null;
                }
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                double cpu = 0;
                long memory = 0;
                long diskWrite = 0;
                int parsedContainers = 0;
                for (String line : output.lines().toList()) {
                    String[] columns = line.split("\\|");
                    if (columns.length != 3) continue;
                    cpu += Double.parseDouble(columns[0].replace("%", "").trim());
                    memory += parseBytes(columns[1].split("/")[0].trim());
                    String[] blockIo = columns[2].split("/");
                    diskWrite += blockIo.length < 2 ? 0 : parseBytes(blockIo[1].trim());
                    parsedContainers++;
                }
                if (parsedContainers != containerNames.size()) return null;
                return new Snapshot(cpu, memory, diskWrite);
            } catch (IOException | InterruptedException | RuntimeException ignored) {
                if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
                return null;
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        }

        /** Counts completed captures without stopping the sampler while the workload is still running. */
        public int sampleCount(long searchStarted, long searchEnded) {
            synchronized (snapshots) {
                return withinWindow(snapshots, searchStarted, searchEnded).size();
            }
        }

        public Usage usage(long searchStarted, long searchEnded) {
            executor.shutdownNow();
            synchronized (snapshots) {
                return summarize(baseline, withinWindow(snapshots, searchStarted, searchEnded));
            }
        }

        static List<Snapshot> withinWindow(List<TimedSnapshot> snapshots, long searchStarted, long searchEnded) {
            return snapshots.stream()
                    .filter(sample -> sample.started() >= searchStarted && sample.ended() <= searchEnded)
                    .map(TimedSnapshot::snapshot).toList();
        }

        static Usage summarize(Snapshot baseline, List<Snapshot> measured) {
            if (measured.isEmpty()) return Usage.UNAVAILABLE;
            double averageCpu = measured.stream().mapToDouble(Snapshot::cpuPercent).average().orElse(0);
            double peakCpu = measured.stream().mapToDouble(Snapshot::cpuPercent).max().orElse(-1);
            long averageMemory = (long) measured.stream().mapToLong(Snapshot::memoryBytes).average().orElse(-1);
            long peakMemory = measured.stream().mapToLong(Snapshot::memoryBytes).max().orElse(-1);
            long firstWrite = baseline == null ? measured.getFirst().diskWriteBytes() : baseline.diskWriteBytes();
            long lastWrite = measured.getLast().diskWriteBytes();
            return new Usage(averageCpu, peakCpu, averageMemory, peakMemory, Math.max(0, lastWrite - firstWrite), measured.size());
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }

        static long parseBytes(String input) {
            String value = input.trim().toUpperCase(Locale.ROOT);
            int index = 0;
            while (index < value.length() && (Character.isDigit(value.charAt(index)) || value.charAt(index) == '.')) index++;
            if (index == 0) return 0;
            double amount = Double.parseDouble(value.substring(0, index));
            String unit = value.substring(index).trim();
            long multiplier = switch (unit) {
                case "KB" -> 1_000L;
                case "MB" -> 1_000_000L;
                case "GB" -> 1_000_000_000L;
                case "TB" -> 1_000_000_000_000L;
                case "KIB" -> 1_024L;
                case "MIB" -> 1_048_576L;
                case "GIB" -> 1_073_741_824L;
                case "TIB" -> 1_099_511_627_776L;
                default -> 1L;
            };
            return (long) (amount * multiplier);
        }
    }

    record Snapshot(double cpuPercent, long memoryBytes, long diskWriteBytes) {
    }

    record TimedSnapshot(Snapshot snapshot, long started, long ended) { }

    public record Usage(
            double averageCpuPercent,
            double peakCpuPercent,
            long averageMemoryBytes,
            long peakMemoryBytes,
            long diskWriteBytes,
            int samples
    ) {
        public static final Usage UNAVAILABLE = new Usage(-1, -1, -1, -1, -1, 0);
    }
}
