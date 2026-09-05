package manualcontrol.crazy;

import java.util.prefs.Preferences;

public final class CrazyConfig {

    public enum BeamMode {
        NONE,
        KAMEHAMEHA,
        HYPNOSIS,
        EVOLUTION,
        ARMY_CANON,
        COPY_CAT_UFO
    }

    private static final Preferences PREFS = Preferences.userRoot().node("manualcontrol/bcu-crazy-patch");

    public boolean manualControl = true;
    public boolean growingUnits;
    public boolean extraPlayerBases;
    public boolean multiBaseHigherForms;
    public boolean interactiveBase;
    public boolean slingshotBase;
    public boolean catCanonBase;
    public boolean slingshotMoneyCost;

    public boolean playerBaseHpOverride;

    public int playerBaseHp = PLAYER_BASE_HP_DEFAULT;

    public boolean moneyLimitOverride;

    public int moneyLimit = MONEY_LIMIT_DEFAULT;
    public static final int PLAYER_BASE_HP_DEFAULT = 50000;
    public static final int PLAYER_BASE_HP_MIN = 1;
    public static final int PLAYER_BASE_HP_MAX = 2_000_000_000;
    public static final int MONEY_LIMIT_DEFAULT = 99999;
    public static final int MONEY_LIMIT_MIN = 0;
    public static final int MONEY_LIMIT_MAX = 9_999_999;
    public boolean bossItem;
    public boolean bombItem;
    public int bombCount = 5;
    public boolean catCoin;
    public boolean theRitual;
    public boolean diceSlot;
    public boolean eggPet;
    public boolean boosterSlot;
    public boolean impactFall;
    public boolean stackUnit;

    public boolean reincarnation;

    public int reincarnationThreshold = REINCARNATION_THRESHOLD_DEFAULT;
    public static final int REINCARNATION_THRESHOLD_DEFAULT = 1500;
    public static final int REINCARNATION_THRESHOLD_MIN = 1;
    public static final int REINCARNATION_THRESHOLD_MAX = 9_999_999;
    public BeamMode beamMode = BeamMode.NONE;

    public double beamRecoverySeconds = 10.0;
    public static final double BEAM_RECOVERY_MIN = 1.0;
    public static final double BEAM_RECOVERY_MAX = 600.0;

    public double ufoSpeedOut = 26.0;
    public double ufoReturnSpeedMul = 2.0;
    public double ufoAltitude = 150.0;
    public double ufoBobAmplitude = 26.0;
    public double ufoBobFrequency = 0.08;
    public double ufoConeWidthMul = 1.0;
    public int ufoSpawnInterval = 8;
    public int ufoTintHue = 150;
    public double ufoDiveDuration = 24.0;
    public static final double UFO_SPEED_MIN = 4.0;
    public static final double UFO_SPEED_MAX = 200.0;
    public static final double UFO_RETURN_MUL_MIN = 1.0;
    public static final double UFO_RETURN_MUL_MAX = 4.0;
    public static final double UFO_ALTITUDE_MIN = 40.0;
    public static final double UFO_ALTITUDE_MAX = 500.0;
    public static final double UFO_BOB_AMP_MIN = 0.0;
    public static final double UFO_BOB_AMP_MAX = 120.0;
    public static final double UFO_BOB_FREQ_MIN = 0.0;
    public static final double UFO_BOB_FREQ_MAX = 0.5;
    public static final double UFO_CONE_MUL_MIN = 0.25;
    public static final double UFO_CONE_MUL_MAX = 4.0;
    public static final int UFO_SPAWN_INTERVAL_MIN = 1;
    public static final int UFO_SPAWN_INTERVAL_MAX = 120;
    public static final int UFO_HUE_MIN = 0;
    public static final int UFO_HUE_MAX = 359;
    public static final double UFO_DIVE_MIN = 1.0;
    public static final double UFO_DIVE_MAX = 120.0;

    public static final int BOMB_COUNT_DEFAULT = 5;
    public static final int BOMB_COUNT_MIN = 1;
    public static final int BOMB_COUNT_MAX = 10;

    public static final double IMPACT_FALL_DEFAULT_MIN_HEIGHT_PX = 600.0;
    public static final double IMPACT_FALL_DEFAULT_MIN_SPEED = 40.0;
    public static final double IMPACT_FALL_DEFAULT_DAMAGE_SCALE = 0.018;
    public static final double IMPACT_FALL_DEFAULT_RADIUS_SCALE = 2.4;
    public static final double IMPACT_FALL_DEFAULT_LAUNCH_SCALE = 1.0;
    public static final double IMPACT_FALL_DEFAULT_CRACK_HOLD_SECONDS = 2.0;
    public static final double IMPACT_FALL_DEFAULT_CRACK_FADE_SECONDS = 5.0;

    public double impactFallMinHeightPx = IMPACT_FALL_DEFAULT_MIN_HEIGHT_PX;
    public double impactFallMinSpeed = IMPACT_FALL_DEFAULT_MIN_SPEED;
    public double impactFallDamageScale = IMPACT_FALL_DEFAULT_DAMAGE_SCALE;
    public double impactFallRadiusScale = IMPACT_FALL_DEFAULT_RADIUS_SCALE;
    public double impactFallLaunchScale = IMPACT_FALL_DEFAULT_LAUNCH_SCALE;
    public double impactFallCrackHoldSeconds = IMPACT_FALL_DEFAULT_CRACK_HOLD_SECONDS;
    public double impactFallCrackFadeSeconds = IMPACT_FALL_DEFAULT_CRACK_FADE_SECONDS;
    public static final double IMPACT_HEIGHT_MIN = 1.0;
    public static final double IMPACT_HEIGHT_MAX = 3000.0;
    public static final double IMPACT_SPEED_MIN = 1.0;
    public static final double IMPACT_SPEED_MAX = 200.0;
    public static final double IMPACT_DAMAGE_SCALE_MIN = 0.001;
    public static final double IMPACT_DAMAGE_SCALE_MAX = 1.0;
    public static final double IMPACT_RADIUS_SCALE_MIN = 0.25;
    public static final double IMPACT_RADIUS_SCALE_MAX = 10.0;
    public static final double IMPACT_LAUNCH_SCALE_MIN = 0.0;
    public static final double IMPACT_LAUNCH_SCALE_MAX = 5.0;
    public static final double IMPACT_CRACK_SECONDS_MIN = 0.0;
    public static final double IMPACT_CRACK_SECONDS_MAX = 30.0;

    public static CrazyConfig defaults() {
        return new CrazyConfig();
    }

    public static CrazyConfig loadRemembered() {
        CrazyConfig c = new CrazyConfig();
        c.manualControl = PREFS.getBoolean("manualControl", true);
        c.growingUnits = PREFS.getBoolean("growingUnits", false);
        c.extraPlayerBases = PREFS.getBoolean("extraPlayerBases", false);
        c.multiBaseHigherForms = PREFS.getBoolean("multiBaseHigherForms", false);
        c.interactiveBase = PREFS.getBoolean("interactiveBase", false);
        c.slingshotBase = PREFS.getBoolean("slingshotBase", false);
        c.catCanonBase = PREFS.getBoolean("catCanonBase", false);
        c.slingshotMoneyCost = PREFS.getBoolean("slingshotMoneyCost", false);
        c.playerBaseHpOverride = PREFS.getBoolean("playerBaseHpOverride", false);
        c.playerBaseHp = clampPlayerBaseHp(PREFS.getInt("playerBaseHp", PLAYER_BASE_HP_DEFAULT));
        c.moneyLimitOverride = PREFS.getBoolean("moneyLimitOverride", false);
        c.moneyLimit = clampMoneyLimit(PREFS.getInt("moneyLimit", MONEY_LIMIT_DEFAULT));
        if (c.catCanonBase) {
            c.interactiveBase = false;
            c.slingshotBase = false;
            c.slingshotMoneyCost = false;
            c.beamMode = BeamMode.NONE;
        } else if (c.interactiveBase && c.slingshotBase) {
            c.slingshotBase = false;
        }
        c.bossItem = PREFS.getBoolean("bossItem", false);
        c.bombItem = PREFS.getBoolean("bombItem", false);
        c.bombCount = clampBombCount(PREFS.getInt("bombCount", BOMB_COUNT_DEFAULT));
        if (c.bombItem) {
            c.bossItem = false;
        }
        c.catCoin = PREFS.getBoolean("catCoin", false);
        c.theRitual = PREFS.getBoolean("theRitual", false);
        c.diceSlot = PREFS.getBoolean("diceSlot", false);
        c.eggPet = PREFS.getBoolean("eggPet", false);
        c.boosterSlot = PREFS.getBoolean("boosterSlot", false);
        c.impactFall = PREFS.getBoolean("impactFall", false);
        c.stackUnit = PREFS.getBoolean("stackUnit", false);
        c.reincarnation = PREFS.getBoolean("reincarnation", false);
        c.reincarnationThreshold = clampReincarnationThreshold(PREFS.getInt("reincarnationThreshold", REINCARNATION_THRESHOLD_DEFAULT));
        try {
            c.beamMode = BeamMode.valueOf(PREFS.get("beamMode", BeamMode.NONE.name()));
        } catch (Throwable ignored) {
            c.beamMode = BeamMode.NONE;
        }
        if (c.catCanonBase) c.beamMode = BeamMode.NONE;

        double legacy = PREFS.getDouble("kameRecoverySeconds", 10.0);
        c.beamRecoverySeconds = clampRecovery(PREFS.getDouble("beamRecoverySeconds", legacy));
        c.ufoSpeedOut = clamp(PREFS.getDouble("ufoSpeedOut", c.ufoSpeedOut), UFO_SPEED_MIN, UFO_SPEED_MAX, 26.0);
        c.ufoReturnSpeedMul = clamp(PREFS.getDouble("ufoReturnSpeedMul2", c.ufoReturnSpeedMul), UFO_RETURN_MUL_MIN, UFO_RETURN_MUL_MAX, 2.0);
        c.ufoAltitude = clamp(PREFS.getDouble("ufoAltitude", c.ufoAltitude), UFO_ALTITUDE_MIN, UFO_ALTITUDE_MAX, 150.0);
        c.ufoBobAmplitude = clamp(PREFS.getDouble("ufoBobAmplitude", c.ufoBobAmplitude), UFO_BOB_AMP_MIN, UFO_BOB_AMP_MAX, 26.0);
        c.ufoBobFrequency = clamp(PREFS.getDouble("ufoBobFrequency", c.ufoBobFrequency), UFO_BOB_FREQ_MIN, UFO_BOB_FREQ_MAX, 0.08);
        c.ufoConeWidthMul = clamp(PREFS.getDouble("ufoConeWidthMul", c.ufoConeWidthMul), UFO_CONE_MUL_MIN, UFO_CONE_MUL_MAX, 1.0);
        c.ufoSpawnInterval = (int) Math.round(clamp(PREFS.getInt("ufoSpawnInterval", c.ufoSpawnInterval), UFO_SPAWN_INTERVAL_MIN, UFO_SPAWN_INTERVAL_MAX, 8));
        c.ufoTintHue = (int) Math.round(clamp(PREFS.getInt("ufoTintHue", c.ufoTintHue), UFO_HUE_MIN, UFO_HUE_MAX, 150));
        c.ufoDiveDuration = clamp(PREFS.getDouble("ufoDiveDuration", c.ufoDiveDuration), UFO_DIVE_MIN, UFO_DIVE_MAX, 24.0);
        c.applyImpactFallDefaults();
        return c;
    }

    public static double clampRecovery(double sec) {
        if (Double.isNaN(sec)) return 10.0;
        return Math.max(BEAM_RECOVERY_MIN, Math.min(BEAM_RECOVERY_MAX, sec));
    }

    public static int clampBombCount(int count) {
        return Math.max(BOMB_COUNT_MIN, Math.min(BOMB_COUNT_MAX, count));
    }

    public static int clampPlayerBaseHp(int hp) {
        return Math.max(PLAYER_BASE_HP_MIN, Math.min(PLAYER_BASE_HP_MAX, hp));
    }

    public static int clampMoneyLimit(int money) {
        return Math.max(MONEY_LIMIT_MIN, Math.min(MONEY_LIMIT_MAX, money));
    }

    public static int clampReincarnationThreshold(int v) {
        return Math.max(REINCARNATION_THRESHOLD_MIN, Math.min(REINCARNATION_THRESHOLD_MAX, v));
    }

    public static double clamp(double value, double min, double max, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    public void remember() {
        applyImpactFallDefaults();
        PREFS.putBoolean("manualControl", manualControl);
        PREFS.putBoolean("growingUnits", growingUnits);
        PREFS.putBoolean("extraPlayerBases", extraPlayerBases);
        PREFS.putBoolean("multiBaseHigherForms", multiBaseHigherForms);
        boolean sling = slingshotBase;
        boolean catCanon = catCanonBase;
        boolean interactive = interactiveBase && !sling && !catCanon;
        if (catCanon) sling = false;
        PREFS.putBoolean("interactiveBase", interactive);
        PREFS.putBoolean("slingshotBase", sling);
        PREFS.putBoolean("catCanonBase", catCanon);
        PREFS.putBoolean("slingshotMoneyCost", slingshotMoneyCost);
        PREFS.putBoolean("playerBaseHpOverride", playerBaseHpOverride);
        PREFS.putInt("playerBaseHp", clampPlayerBaseHp(playerBaseHp));
        PREFS.putBoolean("moneyLimitOverride", moneyLimitOverride);
        PREFS.putInt("moneyLimit", clampMoneyLimit(moneyLimit));
        if (bombItem) bossItem = false;
        PREFS.putBoolean("bossItem", bossItem);
        PREFS.putBoolean("bombItem", bombItem);
        PREFS.putInt("bombCount", clampBombCount(bombCount));
        PREFS.putBoolean("catCoin", catCoin);
        PREFS.putBoolean("theRitual", theRitual);
        PREFS.putBoolean("diceSlot", diceSlot);
        PREFS.putBoolean("eggPet", eggPet);
        PREFS.putBoolean("boosterSlot", boosterSlot);
        PREFS.putBoolean("impactFall", impactFall);
        PREFS.putBoolean("stackUnit", stackUnit);
        PREFS.putBoolean("reincarnation", reincarnation);
        PREFS.putInt("reincarnationThreshold", clampReincarnationThreshold(reincarnationThreshold));
        PREFS.put("beamMode", catCanon ? BeamMode.NONE.name() : (beamMode == null ? BeamMode.NONE.name() : beamMode.name()));
        PREFS.putDouble("beamRecoverySeconds", beamRecoverySeconds);
        PREFS.putDouble("ufoSpeedOut", ufoSpeedOut);
        PREFS.putDouble("ufoReturnSpeedMul2", ufoReturnSpeedMul);
        PREFS.putDouble("ufoAltitude", ufoAltitude);
        PREFS.putDouble("ufoBobAmplitude", ufoBobAmplitude);
        PREFS.putDouble("ufoBobFrequency", ufoBobFrequency);
        PREFS.putDouble("ufoConeWidthMul", ufoConeWidthMul);
        PREFS.putInt("ufoSpawnInterval", ufoSpawnInterval);
        PREFS.putInt("ufoTintHue", ufoTintHue);
        PREFS.putDouble("ufoDiveDuration", ufoDiveDuration);
        PREFS.putDouble("impactFallMinHeightPx", impactFallMinHeightPx);
        PREFS.putDouble("impactFallMinSpeed", impactFallMinSpeed);
        PREFS.putDouble("impactFallDamageScale", impactFallDamageScale);
        PREFS.putDouble("impactFallRadiusScale", impactFallRadiusScale);
        PREFS.putDouble("impactFallLaunchScale", impactFallLaunchScale);
        PREFS.putDouble("impactFallCrackHoldSeconds", impactFallCrackHoldSeconds);
        PREFS.putDouble("impactFallCrackFadeSeconds", impactFallCrackFadeSeconds);
    }

    public CrazyConfig copy() {
        CrazyConfig c = new CrazyConfig();
        c.manualControl = manualControl;
        c.growingUnits = growingUnits;
        c.extraPlayerBases = extraPlayerBases;
        c.multiBaseHigherForms = multiBaseHigherForms;
        c.catCanonBase = catCanonBase;
        c.interactiveBase = catCanonBase ? false : interactiveBase;
        c.slingshotBase = catCanonBase ? false : slingshotBase;
        c.slingshotMoneyCost = c.slingshotBase && slingshotMoneyCost;
        c.playerBaseHpOverride = playerBaseHpOverride;
        c.playerBaseHp = clampPlayerBaseHp(playerBaseHp);
        c.moneyLimitOverride = moneyLimitOverride;
        c.moneyLimit = clampMoneyLimit(moneyLimit);
        c.bombItem = bombItem;
        c.bombCount = clampBombCount(bombCount);
        c.bossItem = c.bombItem ? false : bossItem;
        c.catCoin = catCoin;
        c.theRitual = theRitual;
        c.diceSlot = diceSlot;
        c.eggPet = eggPet;
        c.boosterSlot = boosterSlot;
        c.impactFall = impactFall;
        c.stackUnit = stackUnit;
        c.reincarnation = reincarnation;
        c.reincarnationThreshold = clampReincarnationThreshold(reincarnationThreshold);
        c.beamMode = c.catCanonBase ? BeamMode.NONE : beamMode;
        c.beamRecoverySeconds = beamRecoverySeconds;
        c.ufoSpeedOut = ufoSpeedOut;
        c.ufoReturnSpeedMul = ufoReturnSpeedMul;
        c.ufoAltitude = ufoAltitude;
        c.ufoBobAmplitude = ufoBobAmplitude;
        c.ufoBobFrequency = ufoBobFrequency;
        c.ufoConeWidthMul = ufoConeWidthMul;
        c.ufoSpawnInterval = ufoSpawnInterval;
        c.ufoTintHue = ufoTintHue;
        c.ufoDiveDuration = ufoDiveDuration;
        c.applyImpactFallDefaults();
        return c;
    }

    public void applyImpactFallDefaults() {
        impactFallMinHeightPx = IMPACT_FALL_DEFAULT_MIN_HEIGHT_PX;
        impactFallMinSpeed = IMPACT_FALL_DEFAULT_MIN_SPEED;
        impactFallDamageScale = IMPACT_FALL_DEFAULT_DAMAGE_SCALE;
        impactFallRadiusScale = IMPACT_FALL_DEFAULT_RADIUS_SCALE;
        impactFallLaunchScale = IMPACT_FALL_DEFAULT_LAUNCH_SCALE;
        impactFallCrackHoldSeconds = IMPACT_FALL_DEFAULT_CRACK_HOLD_SECONDS;
        impactFallCrackFadeSeconds = IMPACT_FALL_DEFAULT_CRACK_FADE_SECONDS;
    }

    public boolean hasAnyCrazyFeature() {
        return growingUnits || extraPlayerBases || interactiveBase || slingshotBase || catCanonBase || bossItem || bombItem || catCoin || theRitual || diceSlot || eggPet || boosterSlot || impactFall || stackUnit || reincarnation
                || playerBaseHpOverride || moneyLimitOverride
                || (beamMode != null && beamMode != BeamMode.NONE);
    }
}
