package manualcontrol.crazy.unit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import common.battle.StageBasis;
import common.battle.attack.AtkModelAb;
import common.battle.attack.AtkModelEntity;
import common.battle.attack.AtkModelUnit;
import common.battle.attack.AttackAb;
import common.battle.entity.EntCont;
import common.battle.entity.Entity;
import common.battle.entity.EUnit;
import common.pack.Identifier;
import common.util.Data;
import common.util.unit.AbEnemy;
import common.util.unit.EForm;
import common.util.unit.Form;
import common.util.unit.Unit;

import manualcontrol.Logger;
import manualcontrol.custommap.TileCatalog;
import manualcontrol.reflect.BCUFields;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class SpecialSummonFeature {

    private SpecialSummonFeature() {}

    private static final String FILE_NAME = "special_summon.json";
    private static final String OFFICIAL_PACK = "000000";
    private static final long RETRY_INTERVAL = 750L;

    private static final Gson GSON = new Gson();

    private static volatile Map<Object, Entry> byData = Collections.emptyMap();
    private static volatile Map<String, Entry> byKey = Collections.emptyMap();
    private static long lastAttempt = 0L;

    private static final java.util.Set<Object> hitDone =
            Collections.synchronizedSet(Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>()));
    private static final java.util.Set<Object> killDone =
            Collections.synchronizedSet(Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>()));
    private static final java.util.Set<Object> spawnedBySelf =
            Collections.synchronizedSet(Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>()));

    private static Entry lookup(Object attacker) {
        if (!(attacker instanceof Entity)) return null;
        Object data = ((Entity) attacker).data;
        Entry e = byData.get(data);
        if (e != null) return e;
        String k = deriveKey(data);
        return k == null ? null : byKey.get(k);
    }

    private static String deriveKey(Object data) {
        try {
            if (data instanceof common.battle.data.CustomUnit) {
                Form f = ((common.battle.data.CustomUnit) data).pack;
                if (f != null && f.uid != null) {
                    return f.uid.pack + "/" + f.uid.id + "/" + f.fid;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void onHitOrKill(Object modelObj, Object attackObj, Object targetObj) {
        try {
            if (!(modelObj instanceof AtkModelUnit) || !(targetObj instanceof Entity)) return;
            ensureLoaded();
            AtkModelEntity model = (AtkModelEntity) modelObj;
            Object attacker = model.e;
            if (!(attacker instanceof Entity)) return;
            String dk = deriveKey(((Entity) attacker).data);
            if (isSuperCatMan(dk)) {
                superCatManHit(attackObj, (Entity) attacker, (Entity) targetObj,
                        ((Entity) targetObj).health <= 0L, formOf(dk));
                return;
            }
            if (byKey.isEmpty() && byData.isEmpty()) return;
            if (spawnedBySelf.contains(attacker)) return;
            Entry entry = lookup(attacker);
            dbg("hit/kill hook atk=" + attacker.getClass().getSimpleName()
                    + " derivedKey=" + deriveKey(((Entity) attacker).data)
                    + " entry=" + (entry != null));
            if (entry == null) return;
            Entity target = (Entity) targetObj;
            boolean lethal = target.health <= 0L;
            AtkModelUnit unit = (AtkModelUnit) modelObj;
            if (entry.hit != null) {
                doProc(unit, model, attackObj, entry.hit, hitDone, target, target);
            }
            if (lethal && entry.kill != null) {
                doProc(unit, model, attackObj, entry.kill, killDone, target, target);
            }
        } catch (Throwable t) {
            Logger.err("special-summon hit/kill failed", t);
        }
    }

    public static void onMiss(Object attackObj) {
        try {
            if (!(attackObj instanceof AttackAb)) return;
            ensureLoaded();
            AttackAb attack = (AttackAb) attackObj;
            Object attacker = attack.attacker;
            if (!(attacker instanceof Entity)) return;
            AtkModelAb model = attack.model;
            if (!(model instanceof AtkModelUnit)) return;
            Object captObj = BCUFields.get(attack, "capt");
            int captN = (captObj instanceof List) ? ((List<?>) captObj).size() : -1;
            String dk = deriveKey(((Entity) attacker).data);
            if (isSuperCatMan(dk)) {
                if (formOf(dk) == 0) {
                    Entity first = null;
                    if (captN > 0) {
                        Object o = ((List<?>) captObj).get(0);
                        if (o instanceof Entity) first = (Entity) o;
                    }
                    int tp;
                    if (first != null) {
                        tp = (int) first.pos;
                    } else {
                        int t = scmTargetPos((Entity) attacker);
                        tp = (t != Integer.MIN_VALUE) ? t : (int) ((Entity) attacker).pos;
                    }
                    scmStrike((Entity) attacker, tp);
                }
                return;
            }
            if (captN != 0) return;
            if (byKey.isEmpty() && byData.isEmpty()) return;
            if (spawnedBySelf.contains(attacker)) return;
            Entry entry = lookup(attacker);
            if (entry == null || entry.miss == null) return;
            if (!roll(model, entry.miss.chance)) return;
            int n = copyCount(entry.miss.copies);
            for (int i = 0; i < n; i++) {
                summonTagged((AtkModelUnit) model, model.b, entry.miss.proc, (Entity) attacker, attacker);
            }
        } catch (Throwable t) {
            Logger.err("special-summon miss failed", t);
        }
    }

    private static void doProc(AtkModelUnit unit, AtkModelEntity model, Object atk, Sm sm,
                               java.util.Set<Object> doneSet, Entity ent, Object acs) {
        if (sm.copies == -1) {
            if (roll(model, sm.chance)) summonTagged(unit, model.b, sm.proc, ent, acs);
            return;
        }
        if (atk != null && !doneSet.add(atk)) return;
        if (!roll(model, sm.chance)) return;
        int n = copyCount(sm.copies);
        for (int i = 0; i < n; i++) summonTagged(unit, model.b, sm.proc, ent, acs);
    }

    private static Entity summonTagged(AtkModelUnit unit, StageBasis b, Data.Proc.SUMMON proc,
                                       Entity ent, Object acs) {
        List<EntCont> tempe = b.tempe;
        int before = tempe.size();
        unit.summon(proc, ent, acs, 0);
        Entity last = null;
        for (int i = before; i < tempe.size(); i++) {
            EntCont ec = tempe.get(i);
            if (ec != null && ec.ent != null) {
                spawnedBySelf.add(ec.ent);
                last = ec.ent;
            }
        }
        return last;
    }

    private static int copyCount(int copies) {
        return copies <= 0 ? 1 : copies;
    }

    private static final String SCM_PACK = "UnitCreator";
    private static final int SCM_ID = 0;
    private static final int SCM_ATTACKER_MAX = 24;
    private static final java.util.List<Entity> scmAttackers = new java.util.ArrayList<Entity>();
    private static final java.util.List<Entity> scmSpawners = new java.util.ArrayList<Entity>();
    private static final java.util.Set<Entity> scmDeployed =
            Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
    private static Object scmBasis = null;

    private static boolean isSuperCatMan(String dk) {
        return dk != null && dk.startsWith(SCM_PACK + "/" + SCM_ID + "/");
    }

    private static int formOf(String dk) {
        try {
            return Integer.parseInt(dk.substring(dk.lastIndexOf('/') + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void scmCheckBasis(Object b) {
        if (b != scmBasis) {
            scmBasis = b;
            scmAttackers.clear();
            scmSpawners.clear();
            scmDeployed.clear();
            scmSpent.clear();
            scmAge.clear();
            scmBurrowFF.clear();
            scmAwaitVanish = false;
            scmAwaitClear = false;
            scmHost = null;

            scmLastZeroPos = 0f;
        }
    }

    private static boolean scmGone(Entity e) {
        if (e == null) return true;
        if (e.health <= 0L || e.dead) return true;
        try {
            return BCUFields.getInt(e, "kbTime") == -1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void pruneDead(java.util.List<Entity> list) {
        java.util.Iterator<Entity> it = list.iterator();
        while (it.hasNext()) {
            if (scmGone(it.next())) it.remove();
        }
    }

    private static Data.Proc.SUMMON scmStruct(int form) {
        Data.Proc.SUMMON s = Data.Proc.blank().SUMMON;
        s.id = new Identifier<Unit>(SCM_PACK, Unit.class, SCM_ID);
        s.prob = 100;
        s.dis = 0;
        s.max_dis = 0;
        s.mult = 0;
        s.min_layer = -1;
        s.max_layer = -1;
        s.time = 0;
        s.form = form + 1;
        s.type.anim_type = 0;
        s.type.ignore_limit = true;
        return s;
    }

    private static final int SCM_STAGGER = 5;
    private static float scmLastZeroPos = 0f;
    private static final java.util.Set<Object> scmSpent =
            Collections.synchronizedSet(Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>()));
    private static final java.util.Map<Entity, int[]> scmAge = new java.util.WeakHashMap<Entity, int[]>();

    private static Entity scmHost = null;
    private static boolean scmAwaitVanish = false;
    private static boolean scmAwaitClear = false;
    private static int scmReleasePos = 0;

    private static void scmPhase() {
        if (scmAwaitVanish) {
            if (scmHost == null || !scmGone(scmHost)) return;
            scmAwaitVanish = false;
            int ok = 0;
            for (int f = 1; f <= 4; f++) {
                Entity e = scmSpawnForm(scmHost, f, scmReleasePos, (f - 1) * SCM_STAGGER, 0);
                if (e != null) { scmAttackers.add(e); ok++; }
            }
            scmAwaitClear = true;
            Logger.log("special-summon: SCM 0-0 vanished -> released " + ok + "/4 at " + scmReleasePos);
            return;
        }
        if (scmAwaitClear) {
            pruneDead(scmAttackers);
            if (!scmAttackers.isEmpty()) return;
            scmAwaitClear = false;
            Logger.log("special-summon: SCM attackers all gone -> summon 0-0");
            scmSummonZero(scmHost);
        }
    }
    private static final int SCM_BURROW_SKIP = 50;
    private static final java.util.Set<Object> scmBurrowFF =
            Collections.synchronizedSet(Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>()));

    private static void scmFastForwardBurrow(Entity e) {
        try {
            if (BCUFields.getInt(e, "kbTime") != -4) return;
            scmBurrowFF.remove(e);
            int rem = e.status[47][2];
            if (rem <= 1) return;
            int skip = Math.min(SCM_BURROW_SKIP, rem - 1);
            e.status[47][2] = rem - skip;
            Object ea = BCUFields.get(e.anim, "anim");
            if (ea instanceof common.util.anim.EAnimU) {
                ((common.util.anim.EAnimU) ea).setTime(skip);
            }
            Logger.log("special-summon: SCM burrow skip=" + skip + " remain=" + e.status[47][2]);
        } catch (Throwable t) {
            Logger.err("special-summon: burrow fast-forward failed", t);
        }
    }

    private static int scmLive() {
        int n = 0;
        for (Entity e : scmDeployed) if (!scmGone(e)) n++;
        for (Entity e : scmSpawners) if (!scmGone(e)) n++;
        return n;
    }

    private static int scmTargetPos(Entity s) {
        int range;
        try { range = s.data.getRange(); } catch (Throwable t) { range = 0; }
        if (range <= 0) range = 700;
        range += 150;
        StageBasis sb = s.basis;
        Entity best = null;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < sb.le.size(); i++) {
            Entity o = sb.le.get(i);
            if (o == null || o == s || o.health <= 0L || o.dire == s.dire) continue;
            float d = Math.abs(o.pos - s.pos);
            if (d > range || d >= bestD) continue;
            bestD = d;
            best = o;
        }
        if (best != null) return (int) best.pos;
        try {
            common.battle.entity.AbEntity bs = s.basis.getBase(s.dire);
            if (bs != null && bs.health > 0L && Math.abs(bs.pos - s.pos) <= range) {
                return (int) bs.pos;
            }
        } catch (Throwable ignored) {}
        return Integer.MIN_VALUE;
    }

    private static final int SCM_GRACE = 20;
    private static final int SCM_LIFE = 45;

    public static void scmTick(Object entityObj) {
        try {
            if (!(entityObj instanceof Entity)) return;
            Entity e = (Entity) entityObj;
            if (scmBurrowFF.contains(e)) scmFastForwardBurrow(e);
            scmPhase();
            if (scmGone(e) || !isScmAttacker(e)) return;
            scmCheckBasis(e.basis);
            int[] age = scmAge.get(e);
            if (age == null) { age = new int[]{0}; scmAge.put(e, age); }
            age[0]++;
            if (age[0] >= SCM_LIFE) {
                Logger.log("special-summon: SCM attacker life expired -> cull");
                e.health = 0L;
                return;
            }
            if (age[0] < SCM_GRACE) return;
            if (scmTargetPos(e) != Integer.MIN_VALUE) return;
            Logger.log("special-summon: SCM attacker no target -> cull");
            e.health = 0L;
        } catch (Throwable t) {
            Logger.err("special-summon: scmTick failed", t);
        }
    }

    private static Form scmForm(Object data) {
        try {
            if (data instanceof common.battle.data.CustomUnit) {
                Form f = ((common.battle.data.CustomUnit) data).pack;
                if (f != null && f.uid != null && f.uid.id == SCM_ID && SCM_PACK.equals(f.uid.pack)) return f;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void superCatManHit(Object atk, Entity attacker, Entity target, boolean lethal, int form) {
        scmCheckBasis(attacker.basis);
        if (form != 0) return;
        scmStrike(attacker, (int) target.pos);
    }

    private static void scmStrike(Entity attacker, int targetPos) {
        scmCheckBasis(attacker.basis);
        scmLastZeroPos = attacker.pos;
        scmHost = attacker;
        if (!spawnedBySelf.contains(attacker)) scmDeployed.add(attacker);
        if (scmAwaitVanish || scmAwaitClear) return;
        scmReleasePos = targetPos;
        scmAwaitVanish = true;
        Logger.log("special-summon: SCM 0-0 struck at " + (int) attacker.pos
                + " -> await vanish (target " + targetPos + ")");
    }

    private static void scmSummonZero(Entity killer) {
        if (killer == null) return;
        pruneDead(scmSpawners);
        int cap = scmDeployed.size();
        int live = scmLive();
        if (cap <= 0 || live >= cap) return;
        Entity n = scmSpawnForm(killer, 0, (int) scmLastZeroPos, 0, 2);
        if (n != null) {
            scmSpawners.add(n);
            Logger.log("special-summon: SCM 0-0 burrow at " + (int) scmLastZeroPos
                    + " (cap=" + cap + " live=" + live + ")");
        }
    }

    public static boolean isScmAttacker(Object entityObj) {
        try {
            if (!(entityObj instanceof Entity) || !spawnedBySelf.contains(entityObj)) return false;
            Form f = scmForm(((Entity) entityObj).data);
            return f != null && f.fid != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Entity scmSpawnForm(Entity summoner, int form, int pos, int delay, int animType) {
        try {
            StageBasis sb = summoner.basis;
            Unit u = Identifier.getOr(new Identifier<Unit>(SCM_PACK, Unit.class, SCM_ID), Unit.class);
            if (u == null || u.forms == null || form < 0 || form >= u.forms.length || u.forms[form] == null) return null;
            int lvl = (summoner instanceof EUnit) ? ((EUnit) summoner).lvl : 1;
            EForm ef = new EForm(u.forms[form], lvl);
            EUnit unit = ef.getEntity(sb, null, false, false);
            if (unit == null) return null;
            unit.added(-1, pos);
            sb.tempe.add(new EntCont(unit, delay));
            unit.setSummon(animType, null);
            if (animType == 2) scmBurrowFF.add(unit);
            spawnedBySelf.add(unit);
            return unit;
        } catch (Throwable t) {
            Logger.err("special-summon: scm spawn failed", t);
            return null;
        }
    }

    private static int countLive(java.util.Collection<Entity> c) {
        int n = 0;
        for (Entity e : c) if (e != null && e.health > 0L) n++;
        return n;
    }

    private static boolean roll(AtkModelAb model, int chance) {
        if (chance >= 100) return true;
        return model.b.r.nextFloat() * 100.0f < (float) chance;
    }

    private static long lastDbg = 0L;

    private static void dbg(String s) {
        long now = System.currentTimeMillis();
        if (now - lastDbg < 1000L) return;
        lastDbg = now;
        Logger.log("special-summon DBG: " + s);
    }

    private static synchronized void ensureLoaded() {
        if (!byKey.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAttempt < RETRY_INTERVAL) return;
        lastAttempt = now;

        File file = resolveConfigFile();
        if (file == null) return;

        Map<String, EntryJson> raw = null;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, EntryJson>>() {}.getType();
            raw = GSON.fromJson(reader, type);
        } catch (Throwable t) {
            Logger.err("special-summon: failed reading " + file, t);
            return;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
        if (raw == null || raw.isEmpty()) return;

        Map<Object, Entry> map = new IdentityHashMap<Object, Entry>();
        Map<String, Entry> kmap = new java.util.HashMap<String, Entry>();
        for (Map.Entry<String, EntryJson> e : raw.entrySet()) {
            if (e.getValue() == null) continue;
            Entry entry = buildEntry(e.getValue());
            if (entry.hit == null && entry.kill == null && entry.miss == null) continue;
            kmap.put(e.getKey().trim(), entry);
            Object owner = resolveOwner(e.getKey());
            if (owner != null) map.put(owner, entry);
        }
        if (!kmap.isEmpty()) {
            byData = map;
            byKey = kmap;
            Logger.log("special-summon: loaded " + kmap.size() + " form(s); byKeys=" + kmap.keySet());
        }
    }

    private static File resolveConfigFile() {
        File f = configFile();
        return f.isFile() ? f : null;
    }

    public static File configFile() {
        String home = System.getProperty("manualcontrol.home");
        File homeFile = (home != null && !home.isEmpty()) ? new File(home, FILE_NAME) : null;
        File rootFile = new File(TileCatalog.bcuRoot(), FILE_NAME);
        if (homeFile != null && homeFile.isFile()) return homeFile;
        if (rootFile.isFile()) return rootFile;
        return homeFile != null ? homeFile : rootFile;
    }

    public static synchronized void invalidate() {
        byData = Collections.emptyMap();
        byKey = Collections.emptyMap();
        lastAttempt = 0L;
    }

    private static Object resolveOwner(String key) {
        try {
            String[] parts = key.split("/");
            if (parts.length < 3) return null;
            String pack = parts[0].trim();
            int id = Integer.parseInt(parts[1].trim());
            int form = Integer.parseInt(parts[2].trim());
            Unit u = Identifier.getOr(new Identifier<Unit>(pack, Unit.class, id), Unit.class);
            if (u == null || u.forms == null || form < 0 || form >= u.forms.length) return null;
            Form f = u.forms[form];
            return f == null ? null : f.du;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Entry buildEntry(EntryJson json) {
        Entry entry = new Entry();
        entry.hit = buildSm(json.hit);
        entry.kill = buildSm(json.kill);
        entry.miss = buildSm(json.miss);
        return entry;
    }

    private static Sm buildSm(SummonJson json) {
        if (json == null || json.chance < 1 || json.id == null) return null;
        try {
            String[] parts = json.id.split("/");
            String pack = parts.length > 1 ? parts[0].trim() : OFFICIAL_PACK;
            int id = Integer.parseInt(parts[parts.length - 1].trim());

            Data.Proc.SUMMON s = Data.Proc.blank().SUMMON;
            if (json.enemy) {
                s.id = new Identifier<AbEnemy>(pack, AbEnemy.class, id);
            } else {
                s.id = new Identifier<Unit>(pack, Unit.class, id);
            }
            s.prob = json.chance;
            s.dis = json.minDist;
            s.max_dis = Math.max(json.maxDist, json.minDist);
            s.mult = json.buff;
            s.min_layer = json.layerMin;
            s.max_layer = json.layerMax;
            s.time = json.spawnDelay;
            s.form = json.form + 1;
            s.type.anim_type = json.summonAnim;
            s.type.ignore_limit = json.ignoreLimit;
            s.type.fix_buff = json.fixBuff;
            s.type.same_health = json.sameHealth;
            s.type.bond_hp = json.bondHealth;

            Sm sm = new Sm();
            sm.proc = s;
            sm.chance = json.chance;
            sm.copies = json.copies;
            return sm;
        } catch (Throwable t) {
            Logger.err("special-summon: bad summon config '" + json.id + "'", t);
            return null;
        }
    }

    private static final class Entry {
        Sm hit;
        Sm kill;
        Sm miss;
    }

    private static final class Sm {
        Data.Proc.SUMMON proc;
        int chance;
        int copies;
    }

    static final class EntryJson {
        SummonJson hit;
        SummonJson kill;
        SummonJson miss;
    }

    static final class SummonJson {
        int chance;
        String id;
        int buff;
        int minDist;
        int maxDist;
        int spawnDelay;
        int summonAnim;
        int form;
        int layerMin;
        int layerMax;
        boolean ignoreLimit;
        boolean fixBuff;
        boolean sameHealth;
        boolean bondHealth;
        boolean enemy;
        int copies;
    }
}
