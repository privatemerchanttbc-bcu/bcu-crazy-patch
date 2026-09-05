package manualcontrol.adventure;

import common.battle.StageBasis;
import common.battle.entity.EEnemy;
import common.pack.Identifier;
import common.pack.PackData;
import common.pack.UserProfile;
import common.util.stage.SCDef;
import common.util.unit.AbEnemy;
import common.util.unit.Enemy;
import manualcontrol.Logger;
import manualcontrol.custommap.CustomMapDocument;
import manualcontrol.custommap.CustomMapRuntime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

final class AdventureWaveSpawner {

    private static final int INFINITE_CAP = 6;

    private static final int PER_LINE_CAP = 30;

    private static final int SPAWN_MIN_TICKS = 90;
    private static final int SPAWN_MAX_TICKS = 240;

    private static final float SPAWN_SPREAD = 1600f;
    private static final float SPAWN_MIN_DISTANCE = 400f;
    private static final float SPAWN_EDGE_MARGIN = 250f;

    private final StageBasis sb;
    private final Random rnd = new Random();
    private final ArrayList<Spawn> queue = new ArrayList<Spawn>();
    private int cooldown;
    private int totalPlanned;

    AdventureWaveSpawner(StageBasis sb) {
        this.sb = sb;
        build();
        cooldown = 30;
    }

    private static final class Spawn {
        final AbEnemy enemy;
        final float hpMag;
        final float atkMag;
        final int layer0, layer1, boss, group;
        final float fixedX;
        Spawn(AbEnemy enemy, float hpMag, float atkMag, int l0, int l1, int boss, int group) {
            this(enemy, hpMag, atkMag, l0, l1, boss, group, Float.NaN);
        }
        Spawn(AbEnemy enemy, float hpMag, float atkMag, int l0, int l1, int boss, int group,
              float fixedX) {
            this.enemy = enemy;
            this.hpMag = hpMag;
            this.atkMag = atkMag;
            this.layer0 = l0;
            this.layer1 = l1;
            this.boss = boss;
            this.group = group;
            this.fixedX = fixedX;
        }
    }

    private void build() {
        CustomMapDocument.ModeVariant custom =
                CustomMapRuntime.activeVariant(CustomMapDocument.MapMode.ADVENTURE);
        if (custom != null) {
            buildCustom(custom);
            return;
        }
        float mul = 1f;
        try { mul = sb.est.mul; } catch (Throwable ignored) {}
        SCDef def = sb.st.data;
        if (def == null || def.datas == null) {
            Logger.log("Adventure: stage has no SCDef roster");
            return;
        }
        List<Spawn> normal = new ArrayList<Spawn>();
        List<Spawn> bosses = new ArrayList<Spawn>();
        for (SCDef.Line line : def.datas) {
            if (line == null || line.enemy == null) continue;
            AbEnemy ab = resolve(line.enemy);
            if (ab == null) continue;
            int count = line.number <= 0 ? INFINITE_CAP : Math.min(line.number, PER_LINE_CAP);
            float hpMag = line.multiple * mul * 0.01f;
            int atkPct = line.mult_atk == 0 ? line.multiple : line.mult_atk;
            float atkMag = atkPct * mul * 0.01f;
            for (int i = 0; i < count; i++) {
                Spawn s = new Spawn(ab, hpMag, atkMag, line.layer_0, line.layer_1, line.boss, line.group);
                if (line.boss >= 1) bosses.add(s); else normal.add(s);
            }
        }
        Collections.shuffle(normal, rnd);
        queue.addAll(normal);
        queue.addAll(bosses);
        totalPlanned = queue.size();
        Logger.log("Adventure: wave roster built - " + totalPlanned + " enemies (mul=" + mul + ")");
    }

    private void buildCustom(CustomMapDocument.ModeVariant variant) {
        ArrayList<Enemy> pool = new ArrayList<Enemy>();
        java.util.HashMap<String, Enemy> byId = new java.util.HashMap<String, Enemy>();
        try {
            for (PackData pack : UserProfile.getAllPacks()) {
                if (pack == null || pack.enemies == null) continue;
                List<Enemy> all = pack.enemies.getList();
                if (all == null) continue;
                for (Enemy enemy : all) if (enemy != null && enemy.id != null) {
                    pool.add(enemy);
                    byId.put(enemy.id.pack + ":" + enemy.id.id, enemy);
                }
            }
        } catch (Throwable t) {
            Logger.err("Adventure: could not build custom enemy pool", t);
        }
        if (pool.isEmpty()) return;
        Random seeded = new Random(variant.seed ^ 0x435553544f4dL);
        for (CustomMapDocument.EnemyPlacement placement : variant.enemies) {
            Enemy enemy = placement.enemyId == null || "auto".equalsIgnoreCase(placement.enemyId)
                    ? pool.get(Math.floorMod(seeded.nextInt(), pool.size())) : byId.get(placement.enemyId);
            if (enemy == null) {
                Logger.err("Adventure: custom enemy is unavailable: " + placement.enemyId, null);
                continue;
            }
            float hp = Math.max(0.1f, placement.hpPercent / 100f);
            float attack = Math.max(0.1f, placement.attackPercent / 100f);
            queue.add(new Spawn(enemy, hp, attack, 0, 0, placement.boss ? 1 : 0, 0,
                    variant.worldX(placement.x)));
        }
        totalPlanned = queue.size();
        Logger.log("Adventure: custom roster built - " + totalPlanned + " fixed placements");
    }

    @SuppressWarnings("unchecked")
    private static AbEnemy resolve(Identifier<AbEnemy> id) {
        try {
            return Identifier.get(id);
        } catch (Throwable t) {
            Logger.err("Adventure: could not resolve enemy id " + id, t);
            return null;
        }
    }

    int remainingQueued() { return queue.size(); }

    boolean queueEmpty() { return queue.isEmpty(); }

    int totalPlanned() { return totalPlanned; }

    EEnemy pop(float playerPos) {
        if (queue.isEmpty()) return null;
        float activationDistance = 2400f * CustomMapRuntime.worldScale();
        int index = queue.size() - 1;
        boolean hasFixed = false;
        for (int i = 0; i < queue.size(); i++) {
            Spawn candidate = queue.get(i);
            if (candidate.fixedX == candidate.fixedX) {
                hasFixed = true;
                if (Math.abs(candidate.fixedX - playerPos) <= activationDistance) { index = i; break; }
            }
        }
        if (hasFixed && Math.abs(queue.get(index).fixedX - playerPos) > activationDistance) return null;
        Spawn s = queue.remove(index);
        try {
            EEnemy ee = s.enemy.getEntity(sb, null, s.hpMag, s.atkMag, s.layer0, s.layer1, s.boss, s.group);

            try {
                int[][] status = (int[][]) manualcontrol.reflect.BCUFields.get(ee, "status");
                status[47][0] = 0;
            } catch (Throwable ignored) {}
            float x = s.fixedX == s.fixedX ? s.fixedX : spawnX(playerPos);
            ee.added(1, x);
            if (s.fixedX == s.fixedX) {
                manualcontrol.reflect.EntityAccess.setLayer(ee,
                        Math.round(CustomMapRuntime.surfaceLayerAt(x, ee.currentLayer)));
            }
            return ee;
        } catch (Throwable t) {
            Logger.err("Adventure: enemy spawn failed", t);
            return null;
        }
    }

    private float spawnX(float playerPos) {
        float lo = SPAWN_EDGE_MARGIN;
        float hi = Math.max(lo + 1f, sb.st.len - SPAWN_EDGE_MARGIN);
        for (int attempt = 0; attempt < 8; attempt++) {
            float x = playerPos - SPAWN_SPREAD + rnd.nextFloat() * (SPAWN_SPREAD * 2f);
            if (x < lo) x = lo;
            if (x > hi) x = hi;
            if (Math.abs(x - playerPos) >= SPAWN_MIN_DISTANCE) return x;
        }

        return (playerPos - lo) > (hi - playerPos) ? lo : hi;
    }
}
