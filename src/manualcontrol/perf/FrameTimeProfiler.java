package manualcontrol.perf;

import manualcontrol.FeatureFlags;
import manualcontrol.Logger;
import manualcontrol.ManualControlAgent;
import manualcontrol.OptionalFeatures;
import manualcontrol.reflect.BcuBuildInfo;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Locale;

public final class FrameTimeProfiler {
    private static final PrintStream launchConsole = System.out;
    private static volatile FrameTimeRecorder current;
    private static volatile boolean installed, disabled, exporting;
    private static int capacity = 36000, warmup = 120, targetFps = 60, bcuVersion;
    private static String label = "unlabelled";
    private FrameTimeProfiler() {}

    public static synchronized void initialize(Instrumentation inst) {
        if (!Boolean.getBoolean("manualcontrol.frametime") || installed || disabled) return;
        try {
            ClassLoader loader = FrameTimeProfiler.class.getClassLoader();
            if (!compatible(loader)) throw new IllegalStateException("unsupported diagnostic target fingerprint");
            for (Class<?> loaded : inst.getAllLoadedClasses()) {
                String name = loaded.getName().replace('.', '/');
                if (FrameTimeTransformer.PAINTER.equals(name) || FrameTimeTransformer.FIELD.equals(name))
                    throw new IllegalStateException("diagnostic target already loaded: " + name);
            }
            if (!inst.isRetransformClassesSupported()) throw new IllegalStateException("retransformable hooks unavailable");
            capacity = property("samples", 36000, 32, 120000);
            warmup = property("warmup", 120, 0, 36000);
            targetFps = property("targetFps", 60, 1, 240);
            label = System.getProperty("manualcontrol.frametime.label", "unlabelled")
                    .replaceAll("[\\r\\n\\t]", " ");
            if (label.length() > 160) label = label.substring(0, 160);
            bcuVersion = BcuBuildInfo.detect(loader).versionCode;
            inst.addTransformer(new FrameTimeTransformer(), true);
            installed = true;
            if (!GraphicsEnvironment.isHeadless()) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(event -> {
                    if (event.getID() != KeyEvent.KEY_PRESSED || !event.isControlDown() || !event.isShiftDown()) return false;
                    if (event.getKeyCode() == KeyEvent.VK_F7) { start(); return true; }
                    if (event.getKeyCode() == KeyEvent.VK_F8) { stopAndReport(); return true; }
                    return false;
                });
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                FrameTimeRecorder.Snapshot snapshot = stop();
                if (snapshot != null) report(snapshot);
            }, "BCU-FrameTime-Shutdown"));
            status("ready; Ctrl+Shift+F7 start, Ctrl+Shift+F8 stop/report; target="
                    + targetFps + " warmupDraws=" + warmup + " capacityPerMetric=" + capacity);
            if (Boolean.getBoolean("manualcontrol.frametime.autostart")) start();
        } catch (Throwable error) { disable("initialize", error); }
    }

    private static int property(String key, int fallback, int min, int max) {
        int value = Integer.parseInt(System.getProperty("manualcontrol.frametime." + key, "" + fallback));
        if (value < min || value > max) throw new IllegalArgumentException("invalid " + key);
        return value;
    }

    public static boolean isInstalled() { return installed && !disabled; }
    public static boolean isRecording() { return current != null; }

    public static synchronized boolean start() {
        if (!isInstalled() || current != null || exporting) {
            status(!isInstalled() ? "not available; inspect agent.log" : current != null
                    ? "already recording; capture kept" : "export in progress; wait before starting");
            return false;
        }
        long epoch = System.currentTimeMillis();
        current = new FrameTimeRecorder(capacity, warmup, System.nanoTime(), epoch);
        status("recording armed, label=" + label + "; first battle only; stop before pause/change speed");
        return true;
    }

    public static synchronized FrameTimeRecorder.Snapshot stop() {
        FrameTimeRecorder recorder = current;
        current = null;
        return recorder == null ? null : recorder.close(System.nanoTime());
    }

    public static synchronized void stopAndReport() {
        FrameTimeRecorder.Snapshot snapshot = stop();
        if (snapshot == null) { status("not recording; press Ctrl+Shift+F7 in BCU first"); return; }
        exporting = true;
        status("stopped; exporting report");
        Thread thread = new Thread(() -> {
            try { report(snapshot); } finally { exporting = false; }
        }, "BCU-FrameTime-Export");
        thread.setDaemon(false);
        thread.start();
    }

    private static synchronized void stopChangedBattle(FrameTimeRecorder expected) {
        if (current != expected) return;
        status("battle object changed; stopping this capture. Wait for report complete, then press Ctrl+Shift+F7 again in a stable battle");
        stopAndReport();
    }

    public static long beginDraw(Object owner) {
        FrameTimeRecorder recorder = current;
        if (recorder == null) return 0;
        try {
            long token = recorder.beginDraw(owner, System.nanoTime());
            if (token == 0 && recorder.hasForeignBattleDraws()) stopChangedBattle(recorder);
            return token;
        }
        catch (Throwable error) { disable("draw sample", error); return 0; }
    }
    public static long beginUpdate(Object owner) {
        FrameTimeRecorder recorder = current;
        if (recorder == null) return 0;
        try { return recorder.beginUpdate(owner, System.nanoTime()); }
        catch (Throwable error) { disable("update sample", error); return 0; }
    }
    public static void end(Object owner, int metric, long start, boolean failed) {
        FrameTimeRecorder recorder = current;
        if (recorder == null || start == 0) return;
        try { recorder.end(owner, metric, start, System.nanoTime(), failed); }
        catch (Throwable error) { disable("duration sample", error); }
    }
    public static void disable(String where, Throwable error) {
        disabled = true; current = null;
        status("diagnostics disabled at " + where + "; inspect agent.log");
        try { Logger.err("[FrameTime] diagnostics disabled at " + where + "; gameplay left unchanged", error); }
        catch (Throwable ignored) {  }
    }

    private static void status(String message) {
        String line = "[FrameTime] " + message;
        try { Logger.log(line); } catch (Throwable ignored) { }
        if (System.out != launchConsole) {
            try { launchConsole.println("[ManualControl] " + line); launchConsole.flush(); }
            catch (Throwable ignored) { }
        }
    }

    public static boolean compatible(ClassLoader loader) {
        int version = BcuBuildInfo.detect(loader).versionCode;
        String painter = version == 50808 ? "5607142548f9b3221f2776aadcbde263b998ff875e016e5b3ea689856c27c443"
                : version == 50302 ? "406167f299694981a181224194b21b772624d18a724870191cd97ff2954999ca" : "";
        return !painter.isEmpty() && painter.equals(hash(loader, FrameTimeTransformer.PAINTER))
                && "4adc86d9bd051784988121d9a9045329b1a80d58c17ff6bde0cd3ce2318860f0"
                .equals(hash(loader, FrameTimeTransformer.FIELD));
    }
    private static String hash(ClassLoader loader, String type) {
        try (InputStream stream = loader.getResourceAsStream(type + ".class")) {
            if (stream == null) return "missing";
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) >= 0) if (count > 0) sha.update(buffer, 0, count);
            StringBuilder text = new StringBuilder();
            for (byte b : sha.digest()) text.append(String.format(Locale.ROOT, "%02x", b & 255));
            return text.toString();
        } catch (Exception error) { return "unreadable"; }
    }

    private static void report(FrameTimeRecorder.Snapshot snapshot) {
        try {
            String base = System.getProperty("manualcontrol.frametime.output",
                    new File(System.getProperty("manualcontrol.home", "."), "frame-time-reports").getPath());
            Path folder = writeReport(snapshot, Paths.get(base));
            status("report complete: " + folder.toAbsolutePath());
        } catch (Throwable error) {
            status("report export failed; inspect agent.log");
            Logger.err("[FrameTime] report export failed; gameplay left unchanged", error);
        }
    }

    public static Path writeReport(FrameTimeRecorder.Snapshot snapshot, Path parent) throws IOException {
        Files.createDirectories(parent);
        Path folder = Files.createTempDirectory(parent, "frame-time-");
        double budget = 1000.0 / targetFps;
        try (BufferedWriter raw = Files.newBufferedWriter(folder.resolve("samples.csv"), StandardCharsets.UTF_8)) {
            raw.write("metric,sample,ms,over_target_budget,start_offset_ms,end_offset_ms\n");
            for (int metric = 0; metric < 3; metric++) {
                long[] samples = snapshot.samples[metric];
                for (int i = 0; i < samples.length; i++) {
                    double ms = samples[i] / 1000000.0;
                    long offset = snapshot.startOffsetsNs[metric][i];
                    raw.write(FrameTimeRecorder.METRICS[metric] + "," + (i + 1) + ","
                            + String.format(Locale.ROOT, "%.6f", ms) + "," + (ms > budget) + ","
                            + String.format(Locale.ROOT, "%.6f,%.6f", offset / 1000000.0,
                                    (offset + samples[i]) / 1000000.0) + "\n");
                }
            }
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(folder.resolve("summary.txt"), StandardCharsets.UTF_8))) {
            out.println("BCU Frame-Time Diagnostics - not automatic FPS acceptance");
            out.println("agent=" + ManualControlAgent.VERSION + " bcu=" + bcuVersion + " label=" + label);
            out.println("attackFormRequested=" + FeatureFlags.switchAttackForm + " attackFormState=" + OptionalFeatures.describe("manualcontrol.attackform.AttackFormFeature", "getState")
                    + " newProc=" + FeatureFlags.newProc + " crazy=" + FeatureFlags.crazy);
            out.println("java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name")
                    + " arch=" + System.getProperty("os.arch") + " availableProcessors=" + Runtime.getRuntime().availableProcessors());
            out.println("thresholdTargetFps=" + targetFps + " budgetMs=" + budget + " elapsedNs=" + snapshot.elapsedNs);
            out.println("timingSchema=2 startedEpochMs=" + snapshot.startedEpochMs
                    + " firstDrawOffsetNs=" + snapshot.firstDrawOffsetNs);
            out.println("Offsets use the recording's monotonic clock; cadence spans previous draw start to current draw start.");
            out.println("Epoch anchor is approximate wall-clock correlation only (-1 means unavailable); clock changes may misalign logs.");
            out.println("Independent metric sample numbers are NOT frame IDs; correlate by offset overlap, not row number.");
            out.println("warmupDrawsRemaining=" + snapshot.warmupRemaining + " ignoredOtherBattleDraws=" + snapshot.otherBattleDraws);
            out.println("firstOtherDrawOffsetNs=" + snapshot.firstOtherDrawOffsetNs);
            out.println("captureBoundary=" + (snapshot.otherBattleDraws > 0 ? "battle-object-changed" : "no-foreign-battle-observed"));
            if (snapshot.otherBattleDraws > 0)
                out.println("WARNING: battle object changed; this partial capture must not be used as a full A/B run. Record again after the battle is stable.");
            out.println("Percentiles: nearest rank. Each metric retains its first N samples; excess samples are dropped.");
            out.println("draw_start_interval measures BBPainter callback cadence, NOT GPU/present/display latency.");
            out.println("draw_cpu and battle_update_cpu measure method wall time, including waits/GC, not CPU cycles.");
            out.println("Battle update samples are individual calls (multiple calls per rendered frame at higher speed).");
            out.println("No pause/gap filtering. Stop before pausing, hiding BCU, seeking replay, or changing speed/battle.");
            out.println("Speed and entity count are user-labelled, NOT detected. Keep A/B scene/FPS/other mods identical.");
            for (int metric = 0; metric < 3; metric++) {
                FrameTimeRecorder.Stats stats = new FrameTimeRecorder.Stats(snapshot.samples[metric], budget);
                out.printf(Locale.ROOT, "%s samples=%d avgMs=%.6f p95Ms=%.6f p99Ms=%.6f maxMs=%.6f overBudget=%d over2xBudget=%d over50ms=%d dropped=%d failedCalls=%d%n",
                        FrameTimeRecorder.METRICS[metric], stats.count, stats.averageMs, stats.p95Ms, stats.p99Ms,
                        stats.maxMs, stats.overBudget, stats.over2xBudget, stats.over50ms, snapshot.dropped[metric], snapshot.failures[metric]);
                if (metric == 0) out.printf(Locale.ROOT, "observedCallbackHz=%.6f%n", stats.averageMs > 0 ? 1000 / stats.averageMs : Double.NaN);
            }
            if (out.checkError()) throw new IOException("summary write failed");
        }
        Files.write(folder.resolve("COMPLETE.txt"), "Report export completed.\n".getBytes(StandardCharsets.UTF_8));
        return folder;
    }
}
