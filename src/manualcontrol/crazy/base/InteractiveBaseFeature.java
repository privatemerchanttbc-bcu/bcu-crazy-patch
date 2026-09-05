package manualcontrol.crazy.base;

import common.battle.StageBasis;
import common.battle.attack.AttackCanon;
import common.battle.entity.AbEntity;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.util.Data;
import common.util.unit.EForm;
import common.util.unit.Trait;
import common.pack.UserProfile;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.reflect.BCUFields;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;

public final class InteractiveBaseFeature {

    private static final int SUMMON_COOLDOWN = 300;

    private InteractiveBaseFeature() {}

    public static final class State {
        public boolean dragging;
        public int lastX;
        public int lastY;
        public float offsetX;
        public float offsetY;
        public float velocityX;
        public float velocityY;
        public int cooldown;
        public int shakeScore;
    }

    public static boolean onMousePressed(Object page, MouseEvent e) {
        if (e == null || e.getButton() != MouseEvent.BUTTON1) return false;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
        if (rt == null || !rt.config.interactiveBase) return false;
        if (!hitBase(page, e.getX(), e.getY())) return false;
        rt.interactiveBase.dragging = true;
        rt.interactiveBase.lastX = e.getX();
        rt.interactiveBase.lastY = e.getY();
        rt.interactiveBase.velocityX = 0f;
        rt.interactiveBase.velocityY = 0f;
        rt.interactiveBase.shakeScore = 0;
        Logger.log("BCU Crazy base drag started");
        return true;
    }

    public static boolean onMouseDragged(Object page, MouseEvent e) {
        CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
        if (rt == null || !rt.config.interactiveBase || !rt.interactiveBase.dragging) return false;
        int dx = e.getX() - rt.interactiveBase.lastX;
        int dy = e.getY() - rt.interactiveBase.lastY;
        rt.interactiveBase.velocityX = dx;
        rt.interactiveBase.velocityY = dy;
        rt.interactiveBase.offsetX += dx;
        rt.interactiveBase.offsetY += dy;
        rt.interactiveBase.offsetX = clamp(rt.interactiveBase.offsetX, -260f, 260f);
        rt.interactiveBase.offsetY = clamp(rt.interactiveBase.offsetY, -220f, 160f);
        rt.interactiveBase.lastX = e.getX();
        rt.interactiveBase.lastY = e.getY();
        if (Math.abs(dx) > 18 || Math.abs(dy) > 18) {
            rt.interactiveBase.shakeScore++;
            if (rt.interactiveBase.shakeScore >= 5 && rt.interactiveBase.cooldown <= 0) {
                randomSummon(rt, 10 + randInt((StageBasis) rt.stage, 16));
                rt.interactiveBase.cooldown = SUMMON_COOLDOWN;
                rt.interactiveBase.shakeScore = 0;
            }
        }
        return true;
    }

    public static boolean onMouseReleased(Object page, MouseEvent e) {
        CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
        if (rt == null || !rt.config.interactiveBase || !rt.interactiveBase.dragging) return false;
        rt.interactiveBase.dragging = false;
        float speed = (float) Math.sqrt(rt.interactiveBase.velocityX * rt.interactiveBase.velocityX
                + rt.interactiveBase.velocityY * rt.interactiveBase.velocityY);
        throwShockwave(rt, speed);
        if ((rt.interactiveBase.velocityY > 18f || rt.interactiveBase.offsetY > 100f) && rt.interactiveBase.cooldown <= 0) {
            randomSummon(rt, 10 + randInt((StageBasis) rt.stage, 16));
            rt.interactiveBase.cooldown = SUMMON_COOLDOWN;
        }
        Logger.log("BCU Crazy base released speed=" + Math.round(speed));
        return true;
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt.interactiveBase.cooldown > 0) rt.interactiveBase.cooldown--;
        if (!rt.interactiveBase.dragging) {
            rt.interactiveBase.offsetX *= 0.82f;
            rt.interactiveBase.offsetY *= 0.82f;
            if (Math.abs(rt.interactiveBase.offsetX) < 0.5f) rt.interactiveBase.offsetX = 0f;
            if (Math.abs(rt.interactiveBase.offsetY) < 0.5f) rt.interactiveBase.offsetY = 0f;
        }
    }

    private static boolean hitBase(Object page, int mx, int my) {
        try {
            Object bb = BCUFields.get(page, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            Object bf = BCUFields.get(bbp, "bf");
            Object sb = BCUFields.get(bf, "sb");
            float siz = BCUFields.getFloat(sb, "siz");
            int stagePos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbp, "midh");
            float pos = playerBasePos(sb);
            int x = Math.round((pos * 0.32f + 200f) * siz + stagePos);
            int y = Math.round(midh - 156f * siz);
            boolean hit = Math.abs(mx - x) <= 360f * siz && my <= y + 90f * siz && my >= y - 430f * siz;
            if (!hit) {
                Logger.log("BCU Crazy base hit miss cursor=(" + mx + "," + my + ") base=(" + x + "," + y + ") siz=" + siz);
            }
            return hit;
        } catch (Throwable t) {
            return false;
        }
    }

    private static float playerBasePos(Object stageBasis) {
        try {
            Object st = BCUFields.get(stageBasis, "st");
            return BCUFields.getInt(st, "len") - 800f;
        } catch (Throwable ignored) {
            return BCUFields.getFloat(BCUFields.get(stageBasis, "ubase"), "pos");
        }
    }

    private static void throwShockwave(CrazyRuntime.StageRuntime rt, float speed) {
        StageBasis sb = (StageBasis) rt.stage;
        int damage = Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(speed * 650f)));
        float center = sb.ubase.pos - 180f;
        float radius = 450f + Math.min(700f, speed * 12f);
        int count = 0;
        for (Entity e : new ArrayList<Entity>(sb.le)) {
            if (count >= 40) break;
            if (e == null || e.dead || e.dire != 1) continue;
            if (Math.abs(e.pos - center) <= radius) {
                damage(sb.canon, e, damage);
                count++;
            }
        }
    }

    private static void randomSummon(CrazyRuntime.StageRuntime rt, int count) {
        StageBasis sb = (StageBasis) rt.stage;
        ArrayList<EForm> forms = new ArrayList<EForm>();
        for (int i = 0; i < sb.b.lu.efs.length; i++) {
            for (int j = 0; j < sb.b.lu.efs[i].length; j++) {
                if (sb.b.lu.efs[i][j] != null) forms.add(sb.b.lu.efs[i][j]);
            }
        }
        if (forms.isEmpty()) return;
        for (int n = 0; n < count; n++) {
            EForm f = forms.get(randInt(sb, forms.size()));
            try {
                EUnit eu = f.getEntity(sb, null, false, false);
                float spread = (sb.r.nextFloat() - 0.5f) * 360f;
                eu.added(-1, Math.max(800f, Math.min(sb.st.len - 700f, sb.ubase.pos - 150f + spread)));
                sb.le.add(eu);
            } catch (Throwable t) {
                Logger.err("BCU Crazy base random summon failed", t);
            }
        }
        sb.le.sort(Comparator.comparingInt(e -> e.currentLayer));
        Logger.log("BCU Crazy base random summon count=" + count);
    }

    private static void damage(common.battle.entity.Cannon cannon, AbEntity target, int amount) {
        try {
            ArrayList<Trait> traits = new ArrayList<Trait>();
            traits.add((Trait) UserProfile.getBCData().traits.get(16));
            AttackCanon atk = new AttackCanon(cannon, amount, traits, 0, Data.Proc.blank(),
                    target.pos - 1f, target.pos + 1f, 1);
            target.damaged(atk);
        } catch (Throwable t) {
            Logger.err("BCU Crazy base shockwave damage failed", t);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int randInt(StageBasis sb, int bound) {
        if (bound <= 1) return 0;
        int v = (int) (sb.r.nextFloat() * bound);
        return Math.max(0, Math.min(bound - 1, v));
    }
}
