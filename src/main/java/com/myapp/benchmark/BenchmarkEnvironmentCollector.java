package com.myapp.benchmark;

import org.springframework.boot.SpringBootVersion;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Captures enough immutable context to compare result files without guessing how they were produced. */
public class BenchmarkEnvironmentCollector {

    public Map<String, Object> collect(BenchmarkProperties properties) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        values.put("cpu", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unavailable"));
        values.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        values.put("physicalMemoryBytes", physicalMemoryBytes());
        values.put("javaVersion", System.getProperty("java.version"));
        values.put("springBootVersion", SpringBootVersion.getVersion());
        values.put("dockerServerVersion", command(List.of("docker", "version", "--format", "{{.Server.Version}}")));
        values.put("declaredVectorDbBudget", Map.of(
                "cpuCores", properties.getResourceBudgetCpu(),
                "memoryBytes", properties.getResourceBudgetMemoryBytes()));
        values.put("containerLimits", containerLimits(properties.getContainerNames()));
        values.put("metric", properties.getMetric().name());
        values.put("documentVectorsSha256", sha256(properties.getDocumentVectors()));
        values.put("queryDefinitionsSha256", sha256(properties.getQueryDefinitions()));
        values.put("queryVectorsSha256", sha256(properties.getQueryVectors()));
        return Map.copyOf(values);
    }

    private long physicalMemoryBytes() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean operatingSystem
                ? operatingSystem.getTotalMemorySize()
                : -1L;
    }

    private Map<String, String> containerLimits(List<String> containerNames) {
        Map<String, String> limits = new LinkedHashMap<>();
        for (String container : containerNames) {
            String value = command(List.of("docker", "inspect", "--format",
                    "cpuNano={{.HostConfig.NanoCpus}},memoryBytes={{.HostConfig.Memory}},memorySwapBytes={{.HostConfig.MemorySwap}}",
                    container));
            limits.put(container, value);
        }
        return Map.copyOf(limits);
    }

    private String command(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "unavailable";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 && !output.isBlank() ? output : "unavailable";
        } catch (IOException exception) {
            return "unavailable";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "unavailable";
        }
    }

    public static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash benchmark input: " + path, exception);
        }
    }
}
