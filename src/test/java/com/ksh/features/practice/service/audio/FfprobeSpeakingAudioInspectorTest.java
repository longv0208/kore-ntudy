package com.ksh.features.practice.service.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FfprobeSpeakingAudioInspectorTest {

    @Test
    void acceptsWebmOpusAndCanonicalizesMime() {
        SpeakingAudioInspection result = inspector(success("""
                {"format":{"format_name":"matroska,webm","duration":"2.500"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"2.501"}]}
                """)).inspect(Path.of("private-media"));

        assertThat(result.container()).isEqualTo("webm");
        assertThat(result.codec()).isEqualTo("opus");
        assertThat(result.canonicalMimeType()).isEqualTo("audio/webm");
        assertThat(result.durationMs()).isEqualTo(2501L);
    }

    @Test
    void acceptsMp4AacAndUsesFormatDurationWhenStreamDurationUnavailable() {
        SpeakingAudioInspection result = inspector(success("""
                {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"3.125"},"streams":[{"codec_type":"audio","codec_name":"aac","duration":"N/A"}]}
                """)).inspect(Path.of("private-media"));

        assertThat(result.container()).isEqualTo("mp4");
        assertThat(result.codec()).isEqualTo("aac");
        assertThat(result.canonicalMimeType()).isEqualTo("audio/mp4");
        assertThat(result.durationMs()).isEqualTo(3125L);
    }

    @Test
    void rejectsMismatchedContainerCodecPairs() {
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"1.0"},"streams":[{"codec_type":"audio","codec_name":"aac","duration":"1.0"}]}
                """, SpeakingAudioValidationCategory.UNSUPPORTED_CODEC);
        assertCategory("""
                {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"1.0"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"1.0"}]}
                """, SpeakingAudioValidationCategory.UNSUPPORTED_CODEC);
    }

    @Test
    void rejectsInvalidStreamShapes() {
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"1.0"},"streams":[]}
                """, SpeakingAudioValidationCategory.INVALID_CONTAINER);
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"1.0"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"1.0"},{"codec_type":"audio","codec_name":"opus","duration":"1.0"}]}
                """, SpeakingAudioValidationCategory.MULTIPLE_AUDIO_STREAMS);
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"1.0"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"1.0"},{"codec_type":"video","codec_name":"h264","duration":"1.0"}]}
                """, SpeakingAudioValidationCategory.NON_AUDIO_STREAM_PRESENT);
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"1.0"},"streams":[{"codec_type":"subtitle","codec_name":"webvtt","duration":"1.0"}]}
                """, SpeakingAudioValidationCategory.NON_AUDIO_STREAM_PRESENT);
    }

    @Test
    void rejectsUnknownContainerAndCodec() {
        assertCategory("""
                {"format":{"format_name":"ogg","duration":"1.0"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"1.0"}]}
                """, SpeakingAudioValidationCategory.INVALID_CONTAINER);
        assertCategory("""
                {"format":{"format_name":"matroska","duration":"1.0"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"1.0"}]}
                """, SpeakingAudioValidationCategory.UNSUPPORTED_CODEC);
        assertCategory("""
                {"format":{"format_name":"mov","duration":"1.0"},"streams":[{"codec_type":"audio","codec_name":"aac","duration":"1.0"}]}
                """, SpeakingAudioValidationCategory.UNSUPPORTED_CODEC);
        assertCategory("""
                {"format":{"format_name":"3gp","duration":"1.0"},"streams":[{"codec_type":"audio","codec_name":"aac","duration":"1.0"}]}
                """, SpeakingAudioValidationCategory.UNSUPPORTED_CODEC);
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"1.0"},"streams":[{"codec_type":"audio","codec_name":"mp3","duration":"1.0"}]}
                """, SpeakingAudioValidationCategory.UNSUPPORTED_CODEC);
    }

    @Test
    void rejectsMalformedMissingAndInvalidDuration() {
        assertThatThrownBy(() -> inspector(success("{bad json")).inspect(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.CORRUPT_MEDIA);
        assertCategory("""
                {"format":{"format_name":"matroska,webm"},"streams":[{"codec_type":"audio","codec_name":"opus"}]}
                """, SpeakingAudioValidationCategory.CORRUPT_MEDIA);
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"N/A"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"N/A"}]}
                """, SpeakingAudioValidationCategory.CORRUPT_MEDIA);
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"NaN"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"NaN"}]}
                """, SpeakingAudioValidationCategory.CORRUPT_MEDIA);
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"Infinity"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"Infinity"}]}
                """, SpeakingAudioValidationCategory.CORRUPT_MEDIA);
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"0"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"0"}]}
                """, SpeakingAudioValidationCategory.CORRUPT_MEDIA);
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"-1"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"-1"}]}
                """, SpeakingAudioValidationCategory.CORRUPT_MEDIA);
        assertCategory("""
                []
                """, SpeakingAudioValidationCategory.INVALID_CONTAINER);
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"1.0"},"streams":{"codec_type":"audio"}}
                """, SpeakingAudioValidationCategory.INVALID_CONTAINER);
    }

    @Test
    void rejectsOverDurationAndNonzeroProbeExit() {
        assertThatThrownBy(() -> inspector(success("""
                {"format":{"format_name":"matroska,webm","duration":"601"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"601"}]}
                """)).inspect(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.TOO_LONG);

        assertThatThrownBy(() -> inspector(path -> new FfprobeProcessResult(1, "", "probe failed")).inspect(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.CORRUPT_MEDIA);
    }

    @Test
    void handlesDurationBoundariesPreciselyBeforeRounding() {
        assertThat(inspector(success("""
                {"format":{"format_name":"matroska,webm","duration":"600.0000"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"600.0000"}]}
                """)).inspect(Path.of("private-media")).durationMs()).isEqualTo(600000L);
        assertThat(inspector(success("""
                {"format":{"format_name":"matroska,webm","duration":"599.9999"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"599.9999"}]}
                """)).inspect(Path.of("private-media")).durationMs()).isEqualTo(600000L);
        assertThatThrownBy(() -> inspector(success("""
                {"format":{"format_name":"matroska,webm","duration":"600.0001"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"600.0001"}]}
                """)).inspect(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.TOO_LONG);
        assertCategory("""
                {"format":{"format_name":"matroska,webm","duration":"0.0004"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"0.0004"}]}
                """, SpeakingAudioValidationCategory.CORRUPT_MEDIA);
        assertThat(inspector(success("""
                {"format":{"format_name":"matroska,webm","duration":"5e-4"},"streams":[{"codec_type":"audio","codec_name":"opus","duration":"5e-4"}]}
                """)).inspect(Path.of("private-media")).durationMs()).isEqualTo(1L);
    }

    @Test
    void processRunnerUsesSingleShowEntriesExpressionAndBoundedReaders() {
        AtomicReference<List<String>> commandRef = new AtomicReference<>();
        FakeProcess process = FakeProcess.finished("{\"format\":{},\"streams\":[]}", "bounded stderr");
        DefaultFfprobeProcessRunner runner = new DefaultFfprobeProcessRunner(properties(), command -> {
            commandRef.set(command);
            return process;
        });

        FfprobeProcessResult result = runner.run(Path.of("private-media"));

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("format");
        assertThat(result.getStderr()).isEqualTo("bounded stderr");
        assertThat(commandRef.get()).containsExactly(
                "ffprobe",
                "-v", "error",
                "-show_entries", "format=format_name,duration:stream=index,codec_type,codec_name,duration",
                "-of", "json",
                "private-media"
        );
        assertThat(result.toString()).doesNotContain(result.getStdout());
    }

    @Test
    void processRunnerClassifiesMissingExecutable() {
        DefaultFfprobeProcessRunner runner = new DefaultFfprobeProcessRunner(properties(), command -> {
            throw new IOException("synthetic missing executable");
        });

        assertThatThrownBy(() -> runner.run(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.PROBE_UNAVAILABLE);
    }

    @Test
    void processRunnerRejectsOversizedStdoutAndStderrAndDestroysProcess() {
        FakeProcess stdoutLarge = FakeProcess.finished("xxxxx", "");
        DefaultFfprobeProcessRunner stdoutRunner = new DefaultFfprobeProcessRunner(propertiesWithOutputLimit(4, 64), command -> stdoutLarge);
        assertThatThrownBy(() -> stdoutRunner.run(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.PROBE_OUTPUT_TOO_LARGE);
        assertThat(stdoutLarge.forciblyDestroyed).isTrue();

        FakeProcess stderrLarge = FakeProcess.finished("", "xxxxx");
        DefaultFfprobeProcessRunner stderrRunner = new DefaultFfprobeProcessRunner(propertiesWithOutputLimit(64, 4), command -> stderrLarge);
        assertThatThrownBy(() -> stderrRunner.run(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.PROBE_OUTPUT_TOO_LARGE);
        assertThat(stderrLarge.forciblyDestroyed).isTrue();
    }

    @Test
    void processRunnerTimeoutTerminatesProcessAndInterruptionRestoresFlag() {
        FakeProcess timeout = FakeProcess.neverFinishes();
        DefaultFfprobeProcessRunner timeoutRunner = new DefaultFfprobeProcessRunner(propertiesWithTimeout(Duration.ofMillis(1)), command -> timeout);
        assertThatThrownBy(() -> timeoutRunner.run(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.PROBE_TIMEOUT);
        assertThat(timeout.destroyed).isTrue();
        assertThat(timeout.forciblyDestroyed).isTrue();

        FakeProcess interrupted = FakeProcess.interrupted();
        DefaultFfprobeProcessRunner interruptedRunner = new DefaultFfprobeProcessRunner(properties(), command -> interrupted);
        assertThatThrownBy(() -> interruptedRunner.run(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.PROBE_UNAVAILABLE);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void processRunnerEnrichesDurationFromPacketsWhenContainerHeaderDurationIsMissing() throws IOException {
        Path tempFile = java.nio.file.Files.createTempFile("test-speaking", ".webm");
        try {
            AtomicInteger commandCount = new AtomicInteger();
            DefaultFfprobeProcessRunner runner = new DefaultFfprobeProcessRunner(properties(), command -> {
                int count = commandCount.incrementAndGet();
                if (count == 1) {
                    return FakeProcess.finished("{\"format\":{\"format_name\":\"matroska,webm\"},\"streams\":[{\"codec_type\":\"audio\",\"codec_name\":\"opus\"}]}", "");
                } else {
                    return FakeProcess.finished("0.000000,0.020000\n4.980000,0.020000\n", "");
                }
            });

            FfprobeProcessResult result = runner.run(tempFile);
            assertThat(result.getExitCode()).isZero();
            assertThat(result.getStdout()).contains("\"duration\": \"5.000000\"");
            assertThat(commandCount.get()).isEqualTo(2);
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void propagatesProbeTimeoutUnavailableOutputLimitAndRestoresInterruption() {
        assertThatThrownBy(() -> inspector(path -> {
            throw new SpeakingAudioValidationException(SpeakingAudioValidationCategory.PROBE_TIMEOUT, "Audio probe timed out");
        }).inspect(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.PROBE_TIMEOUT);

        assertThatThrownBy(() -> inspector(path -> {
            throw new SpeakingAudioValidationException(SpeakingAudioValidationCategory.PROBE_OUTPUT_TOO_LARGE, "Audio probe output is too large");
        }).inspect(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.PROBE_OUTPUT_TOO_LARGE);

        AtomicInteger calls = new AtomicInteger();
        FfprobeProcessRunner interrupted = path -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new SpeakingAudioValidationException(SpeakingAudioValidationCategory.PROBE_UNAVAILABLE, "Audio probe was interrupted");
        };
        assertThatThrownBy(() -> inspector(interrupted).inspect(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(SpeakingAudioValidationCategory.PROBE_UNAVAILABLE);
        assertThat(calls).hasValue(1);
        assertThat(Thread.interrupted()).isTrue();
    }

    private void assertCategory(String stdout, SpeakingAudioValidationCategory category) {
        assertThatThrownBy(() -> inspector(success(stdout)).inspect(Path.of("private-media")))
                .isInstanceOf(SpeakingAudioValidationException.class)
                .extracting("category")
                .isEqualTo(category);
    }

    private FfprobeSpeakingAudioInspector inspector(FfprobeProcessRunner runner) {
        return new FfprobeSpeakingAudioInspector(new ObjectMapper(), runner, properties());
    }

    private FfprobeProcessRunner success(String stdout) {
        return path -> new FfprobeProcessResult(0, stdout, "");
    }

    private SpeakingAudioProperties properties() {
        return new SpeakingAudioProperties(
                "private-root",
                "public-root",
                "ffprobe",
                Duration.ofSeconds(10),
                262144,
                65536,
                25 * 1024 * 1024L,
                Duration.ofMinutes(10)
        );
    }

    private SpeakingAudioProperties propertiesWithOutputLimit(int stdout, int stderr) {
        return new SpeakingAudioProperties(
                "private-root",
                "public-root",
                "ffprobe",
                Duration.ofSeconds(10),
                stdout,
                stderr,
                25 * 1024 * 1024L,
                Duration.ofMinutes(10)
        );
    }

    private SpeakingAudioProperties propertiesWithTimeout(Duration timeout) {
        return new SpeakingAudioProperties(
                "private-root",
                "public-root",
                "ffprobe",
                timeout,
                262144,
                65536,
                25 * 1024 * 1024L,
                Duration.ofMinutes(10)
        );
    }

    private static final class FakeProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;
        private final boolean finish;
        private final boolean interrupt;
        boolean destroyed;
        boolean forciblyDestroyed;

        private FakeProcess(String stdout, String stderr, boolean finish, boolean interrupt) {
            this.stdout = new ByteArrayInputStream(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.finish = finish;
            this.interrupt = interrupt;
        }

        static FakeProcess finished(String stdout, String stderr) {
            return new FakeProcess(stdout, stderr, true, false);
        }

        static FakeProcess neverFinishes() {
            return new FakeProcess("", "", false, false);
        }

        static FakeProcess interrupted() {
            return new FakeProcess("", "", false, true);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interrupt) {
                throw new InterruptedException("synthetic interrupt");
            }
            return finish || forciblyDestroyed;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            forciblyDestroyed = true;
            return this;
        }
    }
}
