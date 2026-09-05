package manualcontrol;

import manualcontrol.transform.BBPainterTransformer;
import manualcontrol.transform.BattleInfoPageTransformer;
import manualcontrol.transform.KeyHandlerTransformer;
import manualcontrol.transform.EntityTransformer;
import manualcontrol.transform.SniperTransformer;
import manualcontrol.transform.StageBasisTransformer;
import manualcontrol.transform.CannonTransformer;
import manualcontrol.transform.AttackSimpleTransformer;
import manualcontrol.transform.AttackWaveTransformer;
import manualcontrol.transform.AtkManagerTransformer;
import manualcontrol.transform.FormEditPageTransformer;
import manualcontrol.transform.UnitManagePageTransformer;
import manualcontrol.transform.AtkModelEntityTransformer;
import manualcontrol.transform.AtkModelUnitTransformer;
import manualcontrol.transform.EntityDamageTransformer;
import manualcontrol.transform.PackNoticeTransformer;
import manualcontrol.transform.MainPageTransformer;
import manualcontrol.transform.BattleFieldTransformer;
import manualcontrol.transform.EAnimDTransformer;
import manualcontrol.transform.ConfigPageTransformer;
import manualcontrol.transform.BackgroundTransformer;
import manualcontrol.transform.BGViewPageTransformer;
import manualcontrol.transform.UtilPCTransformer;
import manualcontrol.crazy.CrazyPreloadService;
import manualcontrol.fps.FpsHooks;

import java.lang.instrument.Instrumentation;

public class ManualControlAgent {

    public static final String VERSION = "0.15.2";

    private static final String ATTACK_FORM = "manualcontrol.attackform.AttackFormFeature";
    private static final String NEW_PROC = "manualcontrol.newproc.transform.";
    private static final String DIRECT_EDIT = "manualcontrol.animedit.transform.";
    private static final String SPEED_SCALE = "manualcontrol.speedscale.transform.";

    public static void premain(String args, Instrumentation inst) {
        StartupProfile.agentStarted();
        banner();
        Logger.log("Manual Control + Manual Sniper patch");
        Logger.log("Agent version: " + VERSION);
        Logger.log("JVM: " + System.getProperty("java.version"));
        Logger.log("user.dir: " + System.getProperty("user.dir"));

        if (inst == null) {
            Logger.err("FATAL: Instrumentation API not available", null);
            return;
        }

        FeatureSelectDialog.prompt();
        StartupProfile.featureSelectDone();
        OptionalFeatures.call(ATTACK_FORM, "install",
                new Class<?>[]{Instrumentation.class}, new Object[]{inst});
        final boolean crazy = FeatureFlags.crazy;
        final boolean adventure = FeatureFlags.adventure;
        final boolean hybrid = FeatureFlags.hybrid;
        final boolean directEdit = FeatureFlags.directEdit;

        try {
            if (FeatureFlags.newProc) {
                OptionalFeatures.register(inst, NEW_PROC + "DataProcTransformer", true);
                OptionalFeatures.register(inst, NEW_PROC + "ProcLangTransformer", true);
                OptionalFeatures.register(inst, NEW_PROC + "EditorsTransformer", true);
                OptionalFeatures.register(inst, NEW_PROC + "ProcTableTransformer", true);
                OptionalFeatures.register(inst, NEW_PROC + "ProcIconTransformer", true);
                OptionalFeatures.register(inst, NEW_PROC + "EntityNewProcTransformer", false);
                OptionalFeatures.register(inst, NEW_PROC + "InterpretNewProcTransformer", true);
                OptionalFeatures.register(inst, NEW_PROC + "SwingEditorTransformer", true);
                OptionalFeatures.register(inst, NEW_PROC + "CustomEntitySummonTransformer", true);
            }
            if (FeatureFlags.customMaps) {
                inst.addTransformer(new BackgroundTransformer(), true);
                inst.addTransformer(new BGViewPageTransformer(), true);
                inst.addTransformer(new UtilPCTransformer(), true);
            }
            if (crazy) {
                inst.addTransformer(new BBPainterTransformer(), true);
                inst.addTransformer(new BattleInfoPageTransformer(), true);
                inst.addTransformer(new KeyHandlerTransformer(), true);
                inst.addTransformer(new EntityTransformer(), true);
                inst.addTransformer(new SniperTransformer(), true);
                inst.addTransformer(new StageBasisTransformer(), true);
                inst.addTransformer(new CannonTransformer(), true);
                inst.addTransformer(new AttackSimpleTransformer(), true);
                inst.addTransformer(new AttackWaveTransformer(), true);
                inst.addTransformer(new AtkManagerTransformer(), true);
                inst.addTransformer(new AtkModelEntityTransformer(), true);
                inst.addTransformer(new AtkModelUnitTransformer(), true);
                inst.addTransformer(new EntityDamageTransformer(), true);
                inst.addTransformer(new PackNoticeTransformer(), true);
                inst.addTransformer(new BattleFieldTransformer(), true);
                inst.addTransformer(new EAnimDTransformer(), true);
                inst.addTransformer(new ConfigPageTransformer(), true);
                inst.addTransformer(new FormEditPageTransformer(), true);
                inst.addTransformer(new UnitManagePageTransformer(), true);
            }

            if (crazy || adventure || hybrid
                    || FeatureFlags.miniModes || FeatureFlags.customMaps
                    || FeatureFlags.arena) {
                inst.addTransformer(new MainPageTransformer(), true);
            }

            if (directEdit) {
                OptionalFeatures.register(inst, DIRECT_EDIT + "MaAnimEditPageTransformer", true);
                OptionalFeatures.register(inst, DIRECT_EDIT + "GLAnimBoxTransformer", true);
                OptionalFeatures.register(inst, DIRECT_EDIT + "AnimCETransformer", true);
            }

            if (FeatureFlags.speedScale) {
                OptionalFeatures.register(inst, SPEED_SCALE + "AnimSpeedScaleTransformer", true);
            }
            Logger.log("transformers registered (crazy=" + crazy
                    + " adventure=" + adventure + " hybrid=" + hybrid
                    + " directEdit=" + directEdit
                    + " newProc=" + FeatureFlags.newProc
                    + " switchAttackForm=" + FeatureFlags.switchAttackForm
                    + " attackFormState=" + OptionalFeatures.describe(ATTACK_FORM, "getState") + ")");
            StartupProfile.transformersRegistered();
            if (crazy) {
                ControlTickLoop.start();
                FpsHooks.init();
                CrazyPreloadService.registerDefaultConsumers();
                Logger.log("Crazy preload deferred until the main page is up");
            }
            Logger.log("isRetransformClassesSupported: " + inst.isRetransformClassesSupported());

            Class<?>[] loaded = inst.getAllLoadedClasses();
            Logger.log("Pre-existing loaded classes: " + loaded.length);

            Class<?> loadedBackground = null;
            Class<?> loadedBackgroundPage = null;
            Class<?> loadedUtilPC = null;
            for (Class<?> c : loaded) {
                String n = c.getName();
                if ("common.util.pack.Background".equals(n)) loadedBackground = c;
                if ("page.view.BGViewPage".equals(n)) loadedBackgroundPage = c;
                if ("utilpc.UtilPC".equals(n)) loadedUtilPC = c;
                if (n.startsWith("page.battle.") || n.startsWith("page.awt.") || n.startsWith("common.battle.")) {
                    Logger.log("Pre-existing BCU class: " + n);
                }
            }
            if (FeatureFlags.customMaps && loadedBackground != null
                    && inst.isModifiableClass(loadedBackground)) {
                inst.retransformClasses(loadedBackground);
                Logger.log("Retransformed pre-loaded Background for Custom Stage labels");
            }
            if (FeatureFlags.customMaps && loadedBackgroundPage != null
                    && inst.isModifiableClass(loadedBackgroundPage)) {
                inst.retransformClasses(loadedBackgroundPage);
                Logger.log("Retransformed pre-loaded BGViewPage for Custom Stage catalog");
            }
            if (FeatureFlags.customMaps && loadedUtilPC != null
                    && inst.isModifiableClass(loadedUtilPC)) {
                inst.retransformClasses(loadedUtilPC);
                Logger.log("Retransformed pre-loaded UtilPC for Custom Stage previews");
            }

            if (crazy) {
            Logger.log("Monitor thread starting - output also to file: " + Logger.getLogFile().getAbsolutePath());

            Thread bgChecker = new Thread(() -> {
                int iter = 0;
                boolean patched = false;
                java.util.Set<String> previouslySeen = new java.util.HashSet<>();
                while (iter < 600) {
                    iter++;
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) { break; }
                    try {
                        Class<?>[] now = inst.getAllLoadedClasses();
                        Class<?> target = null;
                        java.util.List<String> newBattleClasses = new java.util.ArrayList<>();
                        for (Class<?> c : now) {
                            String n = c.getName();
                            boolean relevant = n.startsWith("page.battle.")
                                    || n.startsWith("page.awt.")
                                    || n.startsWith("jogl.")
                                    || n.startsWith("common.battle.")
                                    || n.contains("BattleBox")
                                    || n.contains("BBPainter")
                                    || n.contains("BBCtrl")
                                    || n.contains("BBRecd")
                                    || n.contains("BattleInfoPage")
                                    || n.contains("Sniper");
                            if (relevant && !previouslySeen.contains(n)) {
                                newBattleClasses.add(n);
                                previouslySeen.add(n);
                            }
                            if ("page.battle.BattleBox$BBPainter".equals(n)) target = c;
                        }

                        Class<?> infoPage = null;
                        for (Class<?> c : now) {
                            if ("page.battle.BattleInfoPage".equals(c.getName())) {
                                infoPage = c;
                                break;
                            }
                        }
                        Logger.log("[check #" + iter + "] loaded=" + now.length
                                + " transforms=" + BBPainterTransformer.transformCallCount
                                + " BBPainter=" + (target != null ? "YES" : "no")
                                + " InfoPage=" + (infoPage != null ? "YES" : "no")
                                + " newBCU=" + newBattleClasses.size());
                        if (!newBattleClasses.isEmpty()) {
                            for (String s : newBattleClasses) {
                                Logger.log("   NEW: " + s);
                            }
                        }

                        if (infoPage != null && !patched) {
                            Logger.log("Triggering retransform on BattleInfoPage...");
                            try {
                                inst.retransformClasses(infoPage);
                                Logger.log("*** BattleInfoPage retransform COMPLETED ***");
                                patched = true;
                            } catch (Throwable rt) {
                                Logger.err("Retransform BattleInfoPage failed", rt);
                            }
                        }

                    } catch (Throwable t) {
                        Logger.err("Monitor error", t);
                    }
                }
            }, "ManualControl-Monitor");
            bgChecker.setDaemon(true);
            bgChecker.start();
            }
        } catch (Throwable t) {
            Logger.err("Failed to register transformer", t);
        }

        if (Boolean.getBoolean("manualcontrol.frametime"))
            manualcontrol.perf.FrameTimeProfiler.initialize(inst);

        System.out.println("=========================================================");
        System.out.println("[ManualControl] All diagnostic output now goes to file:");
        System.out.println("  " + Logger.getLogFile().getAbsolutePath());
        System.out.println("=========================================================");
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    private static void banner() {
        System.out.println();
        System.out.println("##########################################################");
        System.out.println("##  BCU Manual Control Patch - v" + VERSION);
        System.out.println("##  Build: 2026-09-03");
        System.out.println("##########################################################");
    }
}
