package com.kwang.study.mathvision.workflow.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
public class ManimRenderService {

    private static final Logger log = LoggerFactory.getLogger(ManimRenderService.class);
    private static final String GENERATED_SCENE_FILE = "scene_render.py";
    private static final long MIN_RENDER_TIMEOUT_MINUTES = 10L;
    private static final int RENDER_TIMEOUT_LINES_PER_MINUTE = 120;
    private static final int RENDER_TIMEOUT_MINUTES_PER_SCENE = 2;
    private static final String GEOMETRY_EXPORT_HELPER_FILE = "mathvision_geometry_export.py";
    private static final String GEOMETRY_EXPORT_OUTPUT_FILE = "07_manim_geometry.json";
    private static final String GEOMETRY_EXPORT_HELPER_RESOURCE = "/render/mathvision_geometry_export.py";
    private static final String EDGE_TTS_HELPER_FILE = "mathvision_edge_tts.py";
    private static final String EDGE_TTS_HELPER_RESOURCE = "/render/mathvision_edge_tts.py";
    private static final String GEOMETRY_EXPORT_ENV = "MATHVISION_GEOMETRY_PATH";
    private static final String FATAL_STDERR_MESSAGE = "Fatal Manim traceback detected in stderr";
    private static final Pattern PYTHON_EXCEPTION_LINE = Pattern.compile(
            "^\\s*[A-Za-z_][A-Za-z0-9_.]*(?:Error|Exception):\\s+.+"
    );
    private static final Pattern SCENE_METHOD_DECLARATION = Pattern.compile(
            "(?m)^\\s*def\\s+(scene_[A-Za-z0-9_]*)\\s*\\("
    );
    private static final Pattern SCENE_METHOD_CALL = Pattern.compile(
            "\\bself\\.(scene_[A-Za-z0-9_]*)\\s*\\("
    );

    public Attempt render(String manimCode, String sceneName, String quality, Path outputDir) {
        Instant start = Instant.now();
        Path geometryHelperFile = null;
        Path edgeTtsHelperFile = null;
        try {
            Files.createDirectories(outputDir);
            Path geometryOutputFile = outputDir.resolve(GEOMETRY_EXPORT_OUTPUT_FILE);
            deleteQuietly(geometryOutputFile);

            String effectiveSceneName = StringUtils.hasText(sceneName) ? sceneName : "MainScene";
            String instrumentedCode = manimCode;
            String geometryHelper = loadGeometryExportHelperScript();
            if (StringUtils.hasText(geometryHelper)) {
                geometryHelperFile = outputDir.resolve(GEOMETRY_EXPORT_HELPER_FILE);
                Files.writeString(geometryHelperFile, geometryHelper, StandardCharsets.UTF_8);
                instrumentedCode = instrumentCodeWithGeometryExport(manimCode, effectiveSceneName);
            } else {
                log.warn("MathVision 几何导出脚本不可用, 本次渲染不产出几何证据");
            }

            if (requiresEdgeTtsHelper(manimCode)) {
                String edgeTtsHelper = loadResourceScript(EDGE_TTS_HELPER_RESOURCE);
                if (!StringUtils.hasText(edgeTtsHelper)) {
                    return Attempt.failed("", "MathVision Edge TTS helper is unavailable",
                            false, secondsSince(start), null);
                }
                edgeTtsHelperFile = outputDir.resolve(EDGE_TTS_HELPER_FILE);
                Files.writeString(edgeTtsHelperFile, edgeTtsHelper, StandardCharsets.UTF_8);
            }

            Path codeFile = outputDir.resolve(GENERATED_SCENE_FILE);
            Files.write(codeFile, instrumentedCode.getBytes(StandardCharsets.UTF_8));

            RenderTimeoutBudget timeoutBudget = estimateRenderTimeout(manimCode);
            boolean voiceoverCode = containsVoiceoverCode(manimCode);
            List<String> command = buildCommand(
                    codeFile, effectiveSceneName, quality, outputDir, voiceoverCode);
            log.debug("MathVision Manim 渲染开始, sceneName={}, quality={}, timeout={}min, codeLines={}, scenes={}, outputDir={}",
                    effectiveSceneName, quality, timeoutBudget.timeoutMinutes(),
                    timeoutBudget.codeLines(), timeoutBudget.sceneCount(),
                    outputDir.toAbsolutePath().normalize());
            Process process = startProcess(command, outputDir,
                    geometryHelperFile != null ? geometryOutputFile : null);
            AtomicBoolean fatalStderrDetected = new AtomicBoolean(false);
            AtomicReference<String> fatalStderrLine = new AtomicReference<>();
            ExecutorService readers = Executors.newFixedThreadPool(2);
            Future<String> stdoutFuture = readers.submit(
                    () -> readStream(process.getInputStream(), "stdout", false,
                            null, null, null)
            );
            Future<String> stderrFuture = readers.submit(
                    () -> readStream(process.getErrorStream(), "stderr", true,
                            process, fatalStderrDetected, fatalStderrLine)
            );
            boolean finished;
            try {
                finished = process.waitFor(timeoutBudget.timeoutMinutes(), TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                terminateProcessTree(process, "Render canceled by task request");
                throw e;
            }
            if (!finished) {
                terminateProcessTree(process, "Render timed out after " + timeoutBudget.timeoutMinutes() + " minutes");
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String stdout = awaitStream(stdoutFuture, "stdout");
            String stderr = awaitStream(stderrFuture, "stderr");
            readers.shutdownNow();
            String geometryPath = readGeneratedGeometryPath(geometryOutputFile);
            if (fatalStderrDetected.get()) {
                String failureMessage = append(stderr,
                        FATAL_STDERR_MESSAGE + ": " + fatalStderrLine.get());
                log.warn("MathVision Manim render failed after fatal stderr: {}", fatalStderrLine.get());
                return Attempt.failed(stdout, failureMessage, false, secondsSince(start), geometryPath);
            }
            if (!finished) {
                return Attempt.failed(stdout, append(stderr, "Render timed out after " + timeoutBudget.timeoutMinutes() + " minutes"),
                        true, secondsSince(start), geometryPath);
            }
            int exit = process.exitValue();
            if (exit != 0) {
                return Attempt.failed(stdout, append(stderr, "Manim exited with code " + exit),
                        false, secondsSince(start), geometryPath);
            }
            String videoPath = findVideoFile(outputDir, effectiveSceneName);
            if (!StringUtils.hasText(videoPath)) {
                return Attempt.failed(stdout, append(stderr, "No video file produced"),
                        false, secondsSince(start), geometryPath);
            }
            log.debug("MathVision Manim 渲染成功, sceneName={}, videoPath={}, geometry={}",
                    effectiveSceneName, videoPath, geometryPath);
            return Attempt.success(stdout, stderr, videoPath, secondsSince(start), geometryPath);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("MathVision Manim 渲染异常, sceneName={}, error={}", sceneName, e.getMessage(), e);
            return Attempt.failed("", e.getMessage(), false, secondsSince(start), null);
        } finally {
            deleteQuietly(geometryHelperFile);
            deleteQuietly(edgeTtsHelperFile);
        }
    }

    static RenderTimeoutBudget estimateRenderTimeout(String manimCode) {
        int codeLines = countNonBlankCodeLines(manimCode);
        int sceneCount = Math.max(1, countSceneMethods(manimCode));
        long lineMinutes = divideRoundUp(codeLines, RENDER_TIMEOUT_LINES_PER_MINUTE);
        long sceneMinutes = (long) sceneCount * RENDER_TIMEOUT_MINUTES_PER_SCENE;
        long timeoutMinutes = Math.max(MIN_RENDER_TIMEOUT_MINUTES, lineMinutes + sceneMinutes);
        return new RenderTimeoutBudget(timeoutMinutes, codeLines, sceneCount);
    }

    private static int countNonBlankCodeLines(String manimCode) {
        if (!StringUtils.hasText(manimCode)) {
            return 0;
        }
        int count = 0;
        for (String line : manimCode.split("\\R", -1)) {
            if (StringUtils.hasText(line)) {
                count++;
            }
        }
        return count;
    }

    private static int countSceneMethods(String manimCode) {
        if (!StringUtils.hasText(manimCode)) {
            return 0;
        }
        Set<String> sceneNames = new LinkedHashSet<>();
        java.util.regex.Matcher declarationMatcher = SCENE_METHOD_DECLARATION.matcher(manimCode);
        while (declarationMatcher.find()) {
            sceneNames.add(declarationMatcher.group(1));
        }
        java.util.regex.Matcher callMatcher = SCENE_METHOD_CALL.matcher(manimCode);
        while (callMatcher.find()) {
            sceneNames.add(callMatcher.group(1));
        }
        return sceneNames.size();
    }

    private static long divideRoundUp(long value, long divisor) {
        if (value <= 0) {
            return 0;
        }
        return (value + divisor - 1) / divisor;
    }

    protected Process startProcess(List<String> command, Path outputDir, Path geometryOutputFile)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(outputDir.toFile());
        builder.redirectErrorStream(false);
        builder.environment().put("PYTHONUTF8", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        if (geometryOutputFile != null) {
            builder.environment().put(GEOMETRY_EXPORT_ENV,
                    geometryOutputFile.toAbsolutePath().normalize().toString());
        }
        return builder.start();
    }

    private String instrumentCodeWithGeometryExport(String manimCode, String sceneName) {
        return manimCode
                + System.lineSeparator()
                + System.lineSeparator()
                + "from mathvision_geometry_export import patch_scene_for_geometry_export as __mathvision_patch_scene"
                + System.lineSeparator()
                + sceneName + " = __mathvision_patch_scene(" + sceneName + ")"
                + System.lineSeparator();
    }

    private String loadGeometryExportHelperScript() {
        return loadResourceScript(GEOMETRY_EXPORT_HELPER_RESOURCE);
    }

    private String loadResourceScript(String resourcePath) {
        try (var in = ManimRenderService.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("MathVision failed to load bundled render helper {}: {}", resourcePath, e.getMessage());
            return "";
        }
    }

    static boolean requiresEdgeTtsHelper(String manimCode) {
        return manimCode != null
                && (manimCode.contains("mathvision_edge_tts")
                || manimCode.contains("EdgeTTSService"));
    }

    private String readGeneratedGeometryPath(Path geometryOutputFile) {
        return geometryOutputFile != null && Files.exists(geometryOutputFile)
                ? geometryOutputFile.toAbsolutePath().normalize().toString()
                : null;
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("MathVision 删除临时渲染文件失败 {}: {}", file, e.getMessage());
        }
    }

    private String awaitStream(Future<String> future, String name) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("等待 Manim {} 输出结束超时", name);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待 Manim {} 输出时被中断", name);
            return "";
        } catch (ExecutionException e) {
            log.warn("读取 Manim {} 输出失败: {}", name, e.getCause().getMessage());
            return "";
        }
    }

    protected List<String> buildCommand(Path codeFile,
                                        String sceneName,
                                        String quality,
                                        Path outputDir,
                                        boolean disableCaching) {
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add("manim");
        command.add("render");
        command.add(qualityFlag(quality));
        if (disableCaching) {
            command.add("--disable_caching");
        }
        command.add("--media_dir");
        command.add(outputDir.resolve("media").toAbsolutePath().normalize().toString());
        command.add(codeFile.getFileName().toString());
        command.add(StringUtils.hasText(sceneName) ? sceneName : "MainScene");
        return command;
    }

    static boolean containsVoiceoverCode(String manimCode) {
        return manimCode != null
                && (manimCode.contains("manim_voiceover")
                || manimCode.contains("VoiceoverScene")
                || manimCode.contains("self.voiceover("));
    }

    private String qualityFlag(String quality) {
        if ("high".equalsIgnoreCase(quality)) {
            return "-qh";
        }
        if ("medium".equalsIgnoreCase(quality)) {
            return "-qm";
        }
        return "-ql";
    }

    private String findVideoFile(Path outputDir, String sceneName) {
        Path mediaDir = outputDir.resolve("media").resolve("videos");
        if (!Files.exists(mediaDir)) {
            return null;
        }
        try (var stream = Files.walk(mediaDir, 5)) {
            return stream
                    .filter(path -> path.getFileName() != null)
                    .filter(path -> path.getFileName().toString().endsWith(".mp4"))
                    .filter(path -> !StringUtils.hasText(sceneName)
                            || path.getFileName().toString().contains(sceneName))
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize().toString())
                    .orElse(null);
        } catch (IOException e) {
            log.warn("查找 Manim 输出视频失败: {}", e.getMessage());
            return null;
        }
    }

    private String readStream(java.io.InputStream stream) throws IOException {
        return readStream(stream, "stream", false, null, null, null);
    }

    private String readStream(java.io.InputStream stream,
                              String streamName,
                              boolean errorStream,
                              Process process,
                              AtomicBoolean fatalStderrDetected,
                              AtomicReference<String> fatalStderrLine) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            boolean tracebackSeen = false;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (errorStream) {
                    log.debug("[manim:{}] {}", streamName, line);
                    if (line.contains("Traceback (most recent call last)")) {
                        tracebackSeen = true;
                    } else if (tracebackSeen
                            && isPythonExceptionLine(line)
                            && !isManimControlExceptionLine(line)
                            && fatalStderrDetected != null
                            && fatalStderrDetected.compareAndSet(false, true)) {
                        fatalStderrLine.set(stripPythonExceptionLine(line));
                        terminateProcessTree(process, FATAL_STDERR_MESSAGE);
                    }
                } else {
                    log.debug("[manim:{}] {}", streamName, line);
                }
            }
        }
        return sb.toString();
    }

    private boolean isPythonExceptionLine(String line) {
        return PYTHON_EXCEPTION_LINE.matcher(stripPythonExceptionLine(line)).matches();
    }

    private boolean isManimControlExceptionLine(String line) {
        String exceptionLine = stripPythonExceptionLine(line);
        return exceptionLine.startsWith("EndSceneEarlyException:")
                || exceptionLine.startsWith("RerunSceneException:");
    }

    private String stripPythonExceptionLine(String line) {
        if (line == null) {
            return "";
        }
        String stripped = line.strip();
        int colonIndex = stripped.indexOf(':');
        if (colonIndex <= 0) {
            return stripped;
        }
        int start = colonIndex - 1;
        while (start >= 0) {
            char ch = stripped.charAt(start);
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '.')) {
                break;
            }
            start--;
        }
        return stripped.substring(start + 1).strip();
    }

    private void terminateProcessTree(Process process, String reason) {
        if (process == null) {
            return;
        }
        log.warn("Terminating Manim render process: {}", reason);
        try {
            process.toHandle().descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (UnsupportedOperationException | SecurityException e) {
                    log.debug("Could not terminate descendant process {}: {}", handle.pid(), e.getMessage());
                }
            });
        } catch (UnsupportedOperationException | SecurityException e) {
            log.debug("Could not enumerate Manim descendant processes: {}", e.getMessage());
        }
        process.destroyForcibly();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String append(String value, String message) {
        if (!StringUtils.hasText(value)) {
            return message;
        }
        return value.trim() + "\n" + message;
    }

    private double secondsSince(Instant start) {
        return Duration.between(start, Instant.now()).toMillis() / 1000.0D;
    }

    public static final class Attempt {
        private final boolean success;
        private final String stdout;
        private final String stderr;
        private final String videoPath;
        private final boolean timedOut;
        private final double executionTimeSeconds;
        private final String geometryPath;

        private Attempt(boolean success,
                        String stdout,
                        String stderr,
                        String videoPath,
                        boolean timedOut,
                        double executionTimeSeconds,
                        String geometryPath) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.videoPath = videoPath;
            this.timedOut = timedOut;
            this.executionTimeSeconds = executionTimeSeconds;
            this.geometryPath = geometryPath;
        }

        public static Attempt success(String stdout, String stderr, String videoPath,
                                      double executionTimeSeconds, String geometryPath) {
            return new Attempt(true, stdout, stderr, videoPath, false, executionTimeSeconds, geometryPath);
        }

        public static Attempt failed(String stdout, String stderr, boolean timedOut,
                                     double executionTimeSeconds, String geometryPath) {
            return new Attempt(false, stdout, stderr, null, timedOut, executionTimeSeconds, geometryPath);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public String getVideoPath() {
            return videoPath;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public double getExecutionTimeSeconds() {
            return executionTimeSeconds;
        }

        public String getGeometryPath() {
            return geometryPath;
        }
    }

    static final class RenderTimeoutBudget {
        private final long timeoutMinutes;
        private final int codeLines;
        private final int sceneCount;

        private RenderTimeoutBudget(long timeoutMinutes, int codeLines, int sceneCount) {
            this.timeoutMinutes = timeoutMinutes;
            this.codeLines = codeLines;
            this.sceneCount = sceneCount;
        }

        long timeoutMinutes() {
            return timeoutMinutes;
        }

        int codeLines() {
            return codeLines;
        }

        int sceneCount() {
            return sceneCount;
        }
    }
}
