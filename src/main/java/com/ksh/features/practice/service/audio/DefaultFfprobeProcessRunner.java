package com.ksh.features.practice.service.audio;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class DefaultFfprobeProcessRunner implements FfprobeProcessRunner {
    private static final long DESTROY_GRACE_MILLIS = 200L;

    private final SpeakingAudioProperties properties;
    private final ProcessLauncher processLauncher;

    @Autowired
    public DefaultFfprobeProcessRunner(SpeakingAudioProperties properties) {
        this(properties, command -> new ProcessBuilder(command).start());
    }

    DefaultFfprobeProcessRunner(SpeakingAudioProperties properties, ProcessLauncher processLauncher) {
        this.properties = properties;
        this.processLauncher = processLauncher;
    }

    @Override
    public FfprobeProcessResult run(Path privateMediaPath) {
        List<String> command = List.of(
                properties.getFfprobePath(),
                "-v", "error",
                "-show_entries", "format=format_name,duration:stream=index,codec_type,codec_name,duration",
                "-of", "json",
                privateMediaPath.toString()
        );

        Process process;
        try {
            process = processLauncher.start(command);
        } catch (IOException ex) {
            throw validation(SpeakingAudioValidationCategory.PROBE_UNAVAILABLE, "Audio probe is unavailable", ex);
        }

        ExecutorService readerExecutor = Executors.newFixedThreadPool(2);
        Future<String> stdoutFuture = readerExecutor.submit(() -> readBounded(process.getInputStream(), properties.getMaxProbeStdoutBytes(), process));
        Future<String> stderrFuture = readerExecutor.submit(() -> readBounded(process.getErrorStream(), properties.getMaxProbeStderrBytes(), process));
        try {
            boolean finished = process.waitFor(properties.getFfprobeTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                stdoutFuture.cancel(true);
                stderrFuture.cancel(true);
                throw validation(SpeakingAudioValidationCategory.PROBE_TIMEOUT, "Audio probe timed out");
            }
            String stdout = collect(stdoutFuture);
            String stderr = collect(stderrFuture);
            if (process.exitValue() == 0 && java.nio.file.Files.isRegularFile(privateMediaPath)) {
                stdout = enrichDurationIfMissing(stdout, privateMediaPath);
            }
            return new FfprobeProcessResult(process.exitValue(), stdout, stderr);
        } catch (InterruptedException ex) {
            terminate(process);
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw validation(SpeakingAudioValidationCategory.PROBE_UNAVAILABLE, "Audio probe was interrupted", ex);
        } finally {
            readerExecutor.shutdownNow();
        }
    }

    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }

    private static String collect(Future<String> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof SpeakingAudioValidationException validation) {
                throw validation;
            }
            throw validation(SpeakingAudioValidationCategory.PROBE_UNAVAILABLE, "Audio probe failed", cause);
        }
    }

    private static String readBounded(InputStream input, int limitBytes, Process process) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limitBytes, 8192))) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limitBytes) {
                    process.destroyForcibly();
                    throw validation(SpeakingAudioValidationCategory.PROBE_OUTPUT_TOO_LARGE, "Audio probe output is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(DESTROY_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static SpeakingAudioValidationException validation(SpeakingAudioValidationCategory category, String message) {
        return new SpeakingAudioValidationException(category, message);
    }

    private static SpeakingAudioValidationException validation(SpeakingAudioValidationCategory category, String message, Throwable cause) {
        return new SpeakingAudioValidationException(category, message, cause);
    }

    private String enrichDurationIfMissing(String stdout, Path privateMediaPath) {
        if (stdout == null || stdout.isBlank() || privateMediaPath == null) {
            return stdout;
        }
        if (hasValidDuration(stdout)) {
            return stdout;
        }
        java.math.BigDecimal durationSeconds = extractDurationFromPackets(privateMediaPath);
        if (durationSeconds == null || durationSeconds.signum() <= 0) {
            return stdout;
        }
        return injectFormatDuration(stdout, durationSeconds.toPlainString());
    }

    private java.math.BigDecimal extractDurationFromPackets(Path privateMediaPath) {
        List<String> command = List.of(
                properties.getFfprobePath(),
                "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "packet=pts_time,duration_time",
                "-of", "csv=p=0",
                privateMediaPath.toString()
        );
        Process process;
        try {
            process = processLauncher.start(command);
        } catch (Exception ex) {
            return null;
        }
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        Future<String> outputFuture = readerExecutor.submit(() ->
                readBounded(process.getInputStream(), properties.getMaxProbeStdoutBytes(), process));
        try {
            boolean finished = process.waitFor(properties.getFfprobeTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                outputFuture.cancel(true);
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            String output = outputFuture.get();
            if (output == null || output.isBlank()) {
                return null;
            }
            String[] lines = output.strip().split("\\r?\\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] tokens = line.split(",");
                if (tokens.length > 0 && !tokens[0].isBlank() && !"N/A".equalsIgnoreCase(tokens[0])) {
                    try {
                        java.math.BigDecimal pts = new java.math.BigDecimal(tokens[0].trim());
                        java.math.BigDecimal dur = java.math.BigDecimal.ZERO;
                        if (tokens.length > 1 && !tokens[1].isBlank() && !"N/A".equalsIgnoreCase(tokens[1])) {
                            try {
                                dur = new java.math.BigDecimal(tokens[1].trim());
                            } catch (Exception ignored) {
                            }
                        }
                        java.math.BigDecimal total = pts.add(dur);
                        if (total.signum() > 0) {
                            return total;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            return null;
        } catch (Exception ex) {
            terminate(process);
            return null;
        } finally {
            readerExecutor.shutdownNow();
        }
    }

    private static boolean hasValidDuration(String stdout) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"duration\"\\s*:\\s*\"([0-9]+(?:\\.[0-9]+)?)\"")
                .matcher(stdout);
        while (matcher.find()) {
            try {
                java.math.BigDecimal val = new java.math.BigDecimal(matcher.group(1));
                if (val.signum() > 0) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static String injectFormatDuration(String stdout, String durationStr) {
        if (stdout.contains("\"format\"")) {
            return stdout.replaceFirst(
                    "\"format\"\\s*:\\s*\\{",
                    "\"format\": {\n        \"duration\": \"" + durationStr + "\","
            );
        }
        return stdout;
    }
}
