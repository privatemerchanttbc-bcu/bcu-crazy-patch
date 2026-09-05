package manualcontrol.crazy.base;

import common.battle.StageBasis;
import common.battle.entity.AbEntity;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRuntime;

public final class BaseEditFeature {

    private BaseEditFeature() {}

    public static void onRegister(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return;
        Object stage = rt.stage;
        if (!(stage instanceof StageBasis)) return;
        StageBasis sb = (StageBasis) stage;
        if (rt.config.playerBaseHpOverride) {
            try {
                applyBaseHp(sb, rt.config.playerBaseHp);
            } catch (Throwable t) {
                Logger.err("BCU Crazy player-base-hp override failed", t);
            }
        }
        if (rt.config.moneyLimitOverride) {
            try {
                startWithFullWallet(sb, rt.config.moneyLimit);
            } catch (Throwable t) {
                Logger.err("BCU Crazy money-wallet init failed", t);
            }
        }
    }

    public static void overrideMaxMoney(CrazyRuntime.StageRuntime rt) {
        Object stage = rt.stage;
        if (!(stage instanceof StageBasis)) return;
        ((StageBasis) stage).maxMoney = CrazyRuntimeClamp.moneyInternal(rt.config.moneyLimit);
    }

    private static void applyBaseHp(StageBasis sb, int hp) {
        AbEntity ubase = sb.ubase;
        if (ubase == null) return;
        long value = CrazyRuntimeClamp.baseHp(hp);
        ubase.maxH = value;
        ubase.health = value;
        Logger.log("BCU Crazy player base HP set to " + value);
    }

    private static void startWithFullWallet(StageBasis sb, int moneyLimit) {
        int cap = CrazyRuntimeClamp.moneyInternal(moneyLimit);
        sb.maxMoney = cap;
        sb.money = cap;
        Logger.log("BCU Crazy wallet ceiling=" + cap + " (display " + moneyLimit + "), started full");
    }

    private static final class CrazyRuntimeClamp {
        static long baseHp(int hp) {
            if (hp < 1) return 1L;
            return hp;
        }

        static int moneyInternal(int displayMoney) {
            int m = displayMoney < 0 ? 0 : displayMoney;

            return m * 100;
        }
    }
}
