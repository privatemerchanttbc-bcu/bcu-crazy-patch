package manualcontrol.crazy;

import manualcontrol.Logger;
import manualcontrol.crazy.base.BaseEditFeature;
import manualcontrol.crazy.base.ExtraBasesFeature;
import manualcontrol.crazy.base.InteractiveBaseFeature;
import manualcontrol.crazy.base.CatCanonFeature;
import manualcontrol.crazy.base.SlingshotBaseFeature;
import manualcontrol.crazy.beam.BeamFeature;
import manualcontrol.crazy.beam.ArmyCanonFeature;
import manualcontrol.crazy.beam.CopyCatUfoFeature;
import manualcontrol.crazy.unit.BombItemFeature;
import manualcontrol.crazy.unit.BoosterSlotFeature;
import manualcontrol.crazy.unit.BossItemFeature;
import manualcontrol.crazy.unit.CatCoinFeature;
import manualcontrol.crazy.unit.DiceSlotFeature;
import manualcontrol.crazy.unit.EggPetFeature;
import manualcontrol.crazy.unit.GrowingUnits;
import manualcontrol.crazy.unit.ReincarnationFeature;
import manualcontrol.crazy.unit.StackUnitFeature;
import manualcontrol.crazy.unit.TheRitualFeature;
import manualcontrol.crazy.fall.ImpactFallFeature;
import manualcontrol.reflect.BCUFields;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class CrazyRuntime {

    private static final Map<Object, StageRuntime> STAGES = Collections.synchronizedMap(new WeakHashMap<Object, StageRuntime>());

    private static volatile boolean manualControlEnabled = true;

    private CrazyRuntime() {}

    public static boolean isManualControlEnabled() {
        return manualControlEnabled;
    }

    public static StageRuntime register(Object stageBasis, CrazyConfig config) {
        if (stageBasis == null) return null;
        StageRuntime rt = new StageRuntime(stageBasis, config == null ? CrazyConfig.defaults() : config.copy());
        manualControlEnabled = rt.config.manualControl;
        STAGES.put(stageBasis, rt);
        Logger.log("BCU Crazy registered stage: growing=" + rt.config.growingUnits
                + " stackUnit=" + rt.config.stackUnit
                + " bossItem=" + rt.config.bossItem
                + " bombItem=" + rt.config.bombItem
                + " bombCount=" + rt.config.bombCount
                + " catCoin=" + rt.config.catCoin
                + " theRitual=" + rt.config.theRitual
                + " diceSlot=" + rt.config.diceSlot
                + " eggPet=" + rt.config.eggPet
                + " booster=" + rt.config.boosterSlot
                + " beam=" + rt.config.beamMode
                + " extraBases=" + rt.config.extraPlayerBases
                + " interactiveBase=" + rt.config.interactiveBase
                + " slingshotBase=" + rt.config.slingshotBase
                + " catCanonBase=" + rt.config.catCanonBase
                + " slingshotMoneyCost=" + rt.config.slingshotMoneyCost
                + " playerBaseHp=" + (rt.config.playerBaseHpOverride ? rt.config.playerBaseHp : "off")
                + " moneyLimit=" + (rt.config.moneyLimitOverride ? rt.config.moneyLimit : "off")
                + " impactFall=" + rt.config.impactFall
                + " reincarnation=" + rt.config.reincarnation);
        BaseEditFeature.onRegister(rt);
        return rt;
    }

    public static StageRuntime get(Object stageBasis) {
        if (stageBasis == null) return null;
        return STAGES.get(stageBasis);
    }

    public static StageRuntime getOrCreate(Object stageBasis) {
        StageRuntime rt = get(stageBasis);
        if (rt != null) return rt;
        return register(stageBasis, CrazyConfig.defaults());
    }

    public static Object stageFromPage(Object page) {
        if (page == null) return null;
        try {
            Object bf = BCUFields.get(page, "basis");
            return BCUFields.get(bf, "sb");
        } catch (Throwable t) {
            try {
                Object bb = BCUFields.get(page, "bb");
                Object bbp = BCUFields.get(bb, "bbp");
                Object bf = BCUFields.get(bbp, "bf");
                return BCUFields.get(bf, "sb");
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static StageRuntime runtimeFromPage(Object page) {
        Object stage = stageFromPage(page);
        return stage == null ? null : get(stage);
    }

    public static void tick(Object stageBasis) {
        StageRuntime rt = get(stageBasis);
        if (rt == null || !rt.config.hasAnyCrazyFeature()) return;
        rt.tick();
    }

    public static void afterNativeStageUpdate(Object stageBasis) {
        StageRuntime rt = get(stageBasis);
        if (rt == null || !rt.config.hasAnyCrazyFeature()) return;
        rt.afterNativeStageUpdate();
    }

    public static void overrideMaxMoney(Object stageBasis) {
        StageRuntime rt = get(stageBasis);
        if (rt == null || !rt.config.moneyLimitOverride || rt.isDisabled("money-max-override")) return;
        try {
            BaseEditFeature.overrideMaxMoney(rt);
        } catch (Throwable t) {
            rt.disable("money-max-override", t);
        }
    }

    public static boolean shouldSkipNativeStageUpdate(Object stageBasis) {
        StageRuntime rt = get(stageBasis);
        return rt != null && (BossItemFeature.shouldSkipNativeStageUpdate(rt)
                || CatCoinFeature.shouldSkipNativeStageUpdate(rt)
                || EggPetFeature.shouldSkipNativeStageUpdate(rt)
                || BoosterSlotFeature.shouldSkipNativeStageUpdate(rt));
    }

    public static boolean shouldSkipNativeStageAnimation(Object stageBasis) {
        StageRuntime rt = get(stageBasis);
        return rt != null && (CatCoinFeature.shouldSkipNativeStageUpdate(rt)
                || EggPetFeature.shouldSkipNativeStageUpdate(rt)
                || BoosterSlotFeature.shouldSkipNativeStageUpdate(rt));
    }

    public static int beforeSpawn(Object stageBasis, int row, int col, boolean manual) {
        StageRuntime rt = get(stageBasis);
        if (rt == null) return -1;
        if (BossItemFeature.shouldBlockSpawn(rt) || rt.config.slingshotBase) return 0;
        if (rt.config.catCanonBase) {
            final int[] decision = new int[]{-1};
            safe("cat-canon-spawn", rt, new Runnable() {
                @Override
                public void run() {
                    decision[0] = CatCanonFeature.beforeSpawn(rt, row, col, manual);
                }
            });
            return decision[0];
        }
        if (BoosterSlotFeature.shouldBlockSpawn(rt)) return 0;
        if (rt.config.stackUnit) {
            final int[] decision = new int[]{-1};
            safe("stack-unit-spawn", rt, new Runnable() {
                @Override
                public void run() {
                    decision[0] = StackUnitFeature.beforeSpawn(rt, row, col, manual);
                }
            });
            if (decision[0] >= 0) return decision[0];
        }
        if (rt.config.diceSlot) {
            final int[] decision = new int[]{-1};
            safe("dice-slot-spawn", rt, new Runnable() {
                @Override
                public void run() {
                    decision[0] = DiceSlotFeature.beforeSpawn(rt, row, col, manual);
                }
            });
            return decision[0];
        }
        return -1;
    }

    public static boolean isAnySlingshotActive() {
        synchronized (STAGES) {
            for (StageRuntime rt : STAGES.values()) {
                if (rt != null && rt.config.slingshotBase) return true;
            }
        }
        return false;
    }

    public static boolean isSlingshotActiveFor(Object context) {
        if (context == null) return false;
        try {
            StageRuntime rt = get(context);
            if (rt != null) return rt.config.slingshotBase;
        } catch (Throwable ignored) {}
        try {
            StageRuntime rt = runtimeFromPage(context);
            return rt != null && rt.config.slingshotBase;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isAnyNativePauseActive() {
        synchronized (STAGES) {
            for (StageRuntime rt : STAGES.values()) {
                if (BossItemFeature.shouldSkipNativeStageUpdate(rt)) return true;
                if (EggPetFeature.shouldSkipNativeStageUpdate(rt)) return true;
                if (BoosterSlotFeature.shouldSkipNativeStageUpdate(rt)) return true;
            }
        }
        return false;
    }

    public static void onSpawn(boolean success, Object stageBasis, int row, int col, boolean manual) {
        StageRuntime rt = get(stageBasis);
        if (rt == null) return;
        if (rt.config.diceSlot && DiceSlotFeature.afterNativeSpawnAttempt(rt, row, col, manual, success)) return;
        if (!success || !manual) return;
        if (rt.config.extraPlayerBases) {
            safe("extra-base spawn", rt, new Runnable() {
                @Override
                public void run() {
                    ExtraBasesFeature.onManualSpawn(rt, row, col);
                }
            });
        }
    }

    public static void adjustInRange(java.util.List list, Object stageBasis, int touch, int dire,
                                     float d0, float d1, boolean excludeRightEdge) {
        StageRuntime rt = get(stageBasis);
        if (rt == null || list == null) return;
        if (rt.config.catCanonBase || CatCanonFeature.hasActive(rt)) {
            safe("cat-canon-untarget", rt, new Runnable() {
                @Override
                public void run() {
                    CatCanonFeature.removeProjectilesFromRange(rt, list, dire);
                }
            });
        }
        if (rt.config.extraPlayerBases) {
            safe("extra-base range", rt, new Runnable() {
                @Override
                public void run() {
                    ExtraBasesFeature.appendInRange(rt, list, touch, dire, d0, d1, excludeRightEdge);
                }
            });
        }
        if (rt.config.growingUnits) {
            safe("growing hitbox", rt, new Runnable() {
                @Override
                public void run() {
                    GrowingUnits.appendScaledHitboxes(rt, list, touch, dire, d0, d1, excludeRightEdge);
                }
            });
        }
        if (rt.config.bossItem) {
            safe("boss-item hitbox", rt, new Runnable() {
                @Override
                public void run() {
                    BossItemFeature.appendScaledHitboxes(rt, list, touch, dire, d0, d1, excludeRightEdge);
                }
            });
        }
        if (rt.config.eggPet || EggPetFeature.hasActive(rt)) {
            safe("egg-pet-hitbox", rt, new Runnable() {
                @Override
                public void run() {
                    EggPetFeature.appendScaledHitboxes(rt, list, touch, dire, d0, d1, excludeRightEdge);
                }
            });
        }
        if (rt.config.theRitual || TheRitualFeature.hasActive(rt)) {
            safe("the-ritual-hitbox", rt, new Runnable() {
                @Override
                public void run() {
                    TheRitualFeature.appendScaledHitboxes(rt, list, touch, dire, d0, d1, excludeRightEdge);
                }
            });
        }
        if (rt.config.catCoin || CatCoinFeature.hasActive(rt)) {
            safe("cat-coin-range", rt, new Runnable() {
                @Override
                public void run() {
                    CatCoinFeature.filterInRange(rt, list);
                }
            });
        }
        if (rt.config.stackUnit || StackUnitFeature.hasActive(rt)) {
            safe("stack-unit-hitbox", rt, new Runnable() {
                @Override
                public void run() {
                    StackUnitFeature.appendScaledHitboxes(rt, list, touch, dire, d0, d1, excludeRightEdge);
                }
            });
        }
    }

    public static void safe(String feature, StageRuntime rt, Runnable r) {
        if (rt == null || r == null || rt.isDisabled(feature)) return;
        try {
            r.run();
        } catch (Throwable t) {
            rt.disable(feature, t);
        }
    }

    public static final class StageRuntime {
        public final Object stage;
        public final CrazyConfig config;
        public final GrowingUnits.State growing = new GrowingUnits.State();
        public final BeamFeature.State beam = new BeamFeature.State();
        public final ArmyCanonFeature.State armyCanon = new ArmyCanonFeature.State();
        public final CopyCatUfoFeature.State copyCatUfo = new CopyCatUfoFeature.State();
        public final ExtraBasesFeature.State extraBases = new ExtraBasesFeature.State();
        public final InteractiveBaseFeature.State interactiveBase = new InteractiveBaseFeature.State();
        public final SlingshotBaseFeature.State slingshot = new SlingshotBaseFeature.State();
        public final CatCanonFeature.State catCanon = new CatCanonFeature.State();
        public final BossItemFeature.State bossItem = new BossItemFeature.State();
        public final BombItemFeature.State bombItem = new BombItemFeature.State();
        public final CatCoinFeature.State catCoin = new CatCoinFeature.State();
        public final DiceSlotFeature.State diceSlot = new DiceSlotFeature.State();
        public final EggPetFeature.State eggPet = new EggPetFeature.State();
        public final BoosterSlotFeature.State boosterSlot = new BoosterSlotFeature.State();
        public final TheRitualFeature.State theRitual = new TheRitualFeature.State();
        public final ImpactFallFeature.State impactFall = new ImpactFallFeature.State();
        public final StackUnitFeature.State stackUnit = new StackUnitFeature.State();
        public final ReincarnationFeature.State reincarnation = new ReincarnationFeature.State();
        public final manualcontrol.crazy.unit.SummonAttachFeature.State summonAttach =
                new manualcontrol.crazy.unit.SummonAttachFeature.State();
        private final java.util.Set<String> disabled = new java.util.HashSet<String>();

        private StageRuntime(Object stage, CrazyConfig config) {
            this.stage = stage;
            this.config = config;
        }

        public void tick() {
            if (config.growingUnits) safe("growing", this, new Runnable() {
                @Override
                public void run() {
                    GrowingUnits.tick(StageRuntime.this);
                }
            });
            if (config.bossItem) safe("boss-item", this, new Runnable() {
                @Override
                public void run() {
                    BossItemFeature.tick(StageRuntime.this);
                }
            });
            if (config.bombItem) safe("bomb-item", this, new Runnable() {
                @Override
                public void run() {
                    BombItemFeature.tick(StageRuntime.this);
                }
            });
            if (config.catCoin || CatCoinFeature.hasActive(this)) safe("cat-coin", this, new Runnable() {
                @Override
                public void run() {
                    CatCoinFeature.tick(StageRuntime.this);
                }
            });
            if (config.theRitual || TheRitualFeature.hasActive(this)) safe("the-ritual", this, new Runnable() {
                @Override
                public void run() {
                    TheRitualFeature.tick(StageRuntime.this);
                }
            });
            if (config.diceSlot) safe("dice-slot", this, new Runnable() {
                @Override
                public void run() {
                    DiceSlotFeature.tick(StageRuntime.this);
                }
            });
            if (config.eggPet || EggPetFeature.hasActive(this)) safe("egg-pet", this, new Runnable() {
                @Override
                public void run() {
                    EggPetFeature.tick(StageRuntime.this);
                }
            });
            if (config.boosterSlot) safe("booster-slot", this, new Runnable() {
                @Override
                public void run() {
                    BoosterSlotFeature.tick(StageRuntime.this);
                }
            });
            if (config.extraPlayerBases) safe("extra-bases", this, new Runnable() {
                @Override
                public void run() {
                    ExtraBasesFeature.tick(StageRuntime.this);
                }
            });
            if (config.interactiveBase) safe("interactive-base", this, new Runnable() {
                @Override
                public void run() {
                    InteractiveBaseFeature.tick(StageRuntime.this);
                }
            });
            if (config.slingshotBase) safe("slingshot-base", this, new Runnable() {
                @Override
                public void run() {
                    SlingshotBaseFeature.tick(StageRuntime.this);
                }
            });
            if (config.catCanonBase || CatCanonFeature.hasActive(this)) safe("cat-canon-base", this, new Runnable() {
                @Override
                public void run() {
                    CatCanonFeature.tick(StageRuntime.this);
                }
            });
            if (config.beamMode != null && config.beamMode != CrazyConfig.BeamMode.NONE) safe("beam", this, new Runnable() {
                @Override
                public void run() {
                    BeamFeature.tick(StageRuntime.this);
                    ArmyCanonFeature.tick(StageRuntime.this);
                }
            });
            if (config.beamMode == CrazyConfig.BeamMode.COPY_CAT_UFO) safe("copycat-ufo", this, new Runnable() {
                @Override
                public void run() {
                    CopyCatUfoFeature.tick(StageRuntime.this);
                }
            });
            if (config.impactFall || ImpactFallFeature.hasActive(this)) safe("impact-fall", this, new Runnable() {
                @Override
                public void run() {
                    ImpactFallFeature.tick(StageRuntime.this);
                }
            });
            if (config.stackUnit || StackUnitFeature.hasActive(this)) safe("stack-unit", this, new Runnable() {
                @Override
                public void run() {
                    StackUnitFeature.tick(StageRuntime.this);
                }
            });
            if (config.reincarnation || ReincarnationFeature.hasActive(this)) safe("reincarnation", this, new Runnable() {
                @Override
                public void run() {
                    ReincarnationFeature.tick(StageRuntime.this);
                }
            });

            if (manualcontrol.crazy.unit.SummonAttachFeature.hasActive(this)) {
                safe("summon-attach", this, new Runnable() {
                    @Override
                    public void run() {
                        manualcontrol.crazy.unit.SummonAttachFeature.tick(StageRuntime.this);
                    }
                });
            }
            if (manualcontrol.crazy.collision.DeathLaunchFeature.hasActive()) safe("death-launch", this, new Runnable() {
                @Override
                public void run() {
                    manualcontrol.crazy.collision.DeathLaunchFeature.tick(StageRuntime.this);
                }
            });
            if (manualcontrol.crazy.collision.PhysicalCollision.ENABLED
                    || manualcontrol.crazy.collision.SurgeJuggleFeature.hasActive()) {
                safe("surge-juggle", this, new Runnable() {
                    @Override
                    public void run() {
                        manualcontrol.crazy.collision.SurgeJuggleFeature.tick(StageRuntime.this);
                    }
                });
            }
        }

        public void afterNativeStageUpdate() {
            if (config.bossItem) safe("boss-item-post-stage", this, new Runnable() {
                @Override
                public void run() {
                    BossItemFeature.afterNativeStageUpdate(StageRuntime.this);
                }
            });
            if (config.eggPet || EggPetFeature.hasActive(this)) safe("egg-pet-post-stage", this, new Runnable() {
                @Override
                public void run() {
                    EggPetFeature.afterNativeStageUpdate(StageRuntime.this);
                }
            });
            if (config.boosterSlot) safe("booster-slot-post-stage", this, new Runnable() {
                @Override
                public void run() {
                    BoosterSlotFeature.afterNativeStageUpdate(StageRuntime.this);
                }
            });
            if (config.theRitual || TheRitualFeature.hasActive(this)) safe("the-ritual-post-stage", this, new Runnable() {
                @Override
                public void run() {
                    TheRitualFeature.afterNativeStageUpdate(StageRuntime.this);
                }
            });
            if (manualcontrol.crazy.collision.SurgeJuggleFeature.hasActive()) {
                safe("surge-juggle-post-stage", this, new Runnable() {
                    @Override
                    public void run() {
                        manualcontrol.crazy.collision.SurgeJuggleFeature
                                .afterNativeStageUpdate(StageRuntime.this);
                    }
                });
            }
        }

        public boolean isDisabled(String feature) {
            return disabled.contains(feature);
        }

        public void disable(String feature, Throwable t) {
            if (disabled.add(feature)) {
                Logger.err("BCU Crazy feature disabled for this battle: " + feature, t);
            }
        }
    }
}
