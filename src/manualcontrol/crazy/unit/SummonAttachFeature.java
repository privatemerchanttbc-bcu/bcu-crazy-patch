package manualcontrol.crazy.unit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import common.battle.attack.AtkModelEntity;
import common.battle.attack.AtkModelUnit;
import common.battle.attack.AttackAb;
import common.battle.entity.Entity;
import common.pack.Identifier;
import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;
import common.util.Data;
import common.util.anim.AnimU;
import common.util.anim.EAnimU;
import common.util.anim.EPart;
import common.util.unit.Form;
import common.util.unit.Unit;

import manualcontrol.HoldState;
import manualcontrol.Logger;
import manualcontrol.crazy.collision.AnimGeometry;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.custommap.TileCatalog;
import manualcontrol.render.ModeText;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;

import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class SummonAttachFeature {

    private SummonAttachFeature() {}

    private static final String FILE_NAME = "summon_attach.json";
    private static final long RETRY_INTERVAL = 750L;
    private static final float POS_TO_PX = 0.32f;

    private static final Gson GSON = new Gson();

    private static volatile Map<String, Cfg> byKey = Collections.emptyMap();
    private static volatile Map<Object, Map<Integer, Cfg>> byData = Collections.emptyMap();
    private static volatile boolean loadFailed = false;
    private static long lastAttempt = 0L;

    private static final Map<Object, Integer> ATTACK_INDEX = Collections.synchronizedMap(
            new WeakHashMap<Object, Integer>());

    private static boolean releasing = false;
    private static volatile int active = 0;

    public static final class State {
        public final List<Carry> carries = new ArrayList<Carry>();
    }

    public static final class Carry {
        Entity summoner;
        AtkModelUnit model;
        Entity ref;
        Data.Proc.SUMMON proc;
        Object acs;
        int resist;
        int atkIndex;
        Cfg cfg;
        EAnimU ghost;
        int phase;
        boolean rewound;
        float fallPos;
        float fallHeight;
        float fallSpeed;
        int layer;
    }

    static final class Cfg {
        boolean enabled = true;
        String anim = "ATK";
        int part = 0;
        int summonFrame = 0;
        int releaseFrame = 0;
        float offsetX = 0f;
        float offsetY = 0f;
        String ghostAnim = "WALK";
        float gravity = HoldState.GRAVITY;
        float scale = 1f;
    }

    public static boolean hasActive(CrazyRuntime.StageRuntime rt) {
        return rt != null && !rt.summonAttach.carries.isEmpty();
    }

    public static void noteAttackIndex(Object model, int ind) {
        if (model == null || ind < 0) return;
        ATTACK_INDEX.put(model, Integer.valueOf(ind));
    }

    public static void noteAttackFromAttack(Object model, Object attackObj) {
        if (model == null || !(attackObj instanceof AttackAb)) return;
        try {
            Object ind = BCUFields.get(attackObj, "ind");
            if (ind instanceof Integer) noteAttackIndex(model, ((Integer) ind).intValue());
        } catch (Throwable ignored) {}
    }

    public static boolean onSummon(Object modelObj, Object procObj, Object entObj,
                                   Object acsObj, int resist) {
        try {
            if (releasing) return false;
            if (!(modelObj instanceof AtkModelUnit)) return false;
            if (!(procObj instanceof Data.Proc.SUMMON)) return false;
            ensureLoaded();
            if (byKey.isEmpty() && byData.isEmpty()) return false;

            AtkModelUnit model = (AtkModelUnit) modelObj;
            Object attacker = ((AtkModelEntity) model).e;
            if (!(attacker instanceof Entity)) return false;
            Entity summoner = (Entity) attacker;

            int ind = attackIndex(model);
            Cfg cfg = lookup(summoner, ind);
            if (cfg == null || !cfg.enabled) return false;

            CrazyRuntime.StageRuntime rt = CrazyRuntime.get(summoner.basis);
            if (rt == null) return false;
            State st = rt.summonAttach;

            for (int i = 0; i < st.carries.size(); i++) {
                Carry ex = st.carries.get(i);
                if (ex != null && ex.summoner == summoner && ex.atkIndex == ind && ex.phase < 2) {
                    return true;
                }
            }

            Carry c = new Carry();
            c.summoner = summoner;
            c.model = model;
            c.ref = (entObj instanceof Entity) ? (Entity) entObj : summoner;
            c.acs = acsObj;
            c.resist = resist;
            c.atkIndex = ind;
            c.cfg = cfg;
            c.proc = copyProc((Data.Proc.SUMMON) procObj);
            c.ghost = buildGhost(c.proc, cfg);
            c.phase = 0;
            st.carries.add(c);
            active = st.carries.size();
            Logger.log("summon-attach: armed key=" + deriveKey(summoner.data) + "/" + ind
                    + " part=" + cfg.part + " anim=" + cfg.anim
                    + " frames=" + cfg.summonFrame + "-" + cfg.releaseFrame);
            return true;
        } catch (Throwable t) {
            Logger.err("summon-attach: onSummon failed", t);
            return false;
        }
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return;
        State st = rt.summonAttach;
        if (st.carries.isEmpty()) return;
        Iterator<Carry> it = st.carries.iterator();
        while (it.hasNext()) {
            Carry c = it.next();
            if (c == null || !tickCarry(c)) it.remove();
        }
        active = st.carries.size();
    }

    private static boolean tickCarry(Carry c) {
        try {
            if (c.phase == 2) return tickFall(c);
            if (c.summoner == null) return false;
            if (c.summoner.dead || c.summoner.health <= 0L) {
                startFall(c);
                return true;
            }
            EAnimU ea = liveAnim(c.summoner);
            if (ea == null) return true;
            boolean onAnim = ea.type != null && ea.type.name().equals(c.cfg.anim);
            float f = ea.f;
            if (c.phase == 0) {
                if (!onAnim) return true;
                if (!c.rewound) {
                    if (f < c.cfg.summonFrame) c.rewound = true;
                    return true;
                }
                if (f < c.cfg.summonFrame) return true;
                c.phase = 1;
            }
            if (c.ghost != null) c.ghost.update(false);
            if (onAnim && (f >= c.cfg.releaseFrame || f >= ea.len() - 1)) startFall(c);
            return true;
        } catch (Throwable t) {
            Logger.err("summon-attach: carry tick failed", t);
            return false;
        }
    }

    private static boolean tickFall(Carry c) {
        c.fallSpeed += c.cfg.gravity;
        c.fallHeight -= c.fallSpeed;
        if (c.fallHeight <= 0f) {
            spawn(c);
            return false;
        }
        if (c.ghost != null) c.ghost.update(false);
        return true;
    }

    private static void startFall(Carry c) {
        float[] o = partOffset(c);
        float px = c.summoner != null ? c.summoner.pos : 0f;
        float offX = (o != null ? o[0] : 0f) + c.cfg.offsetX;
        float offY = (o != null ? o[1] : 0f) + c.cfg.offsetY;
        c.fallPos = px + offX / POS_TO_PX;
        c.fallHeight = Math.max(0f, -offY);
        c.fallSpeed = 0f;
        c.layer = c.summoner != null ? c.summoner.currentLayer : 0;
        c.phase = 2;
        Logger.log("summon-attach: released at pos=" + Math.round(c.fallPos)
                + " height=" + Math.round(c.fallHeight));
    }

    private static void spawn(Carry c) {
        try {
            if (c.model == null || c.proc == null) return;
            Entity ref = c.ref != null ? c.ref : c.summoner;
            if (ref == null) return;
            int dire = 1;
            try { dire = c.model.getDire(); } catch (Throwable ignored) {}
            if (dire == 0) dire = 1;
            Data.Proc.SUMMON copy = copyProc(c.proc);
            int dis = Math.round((c.fallPos - ref.pos) / dire);
            copy.dis = dis;
            copy.max_dis = dis;
            releasing = true;
            try {
                c.model.summon(copy, ref, c.acs, c.resist);
            } finally {
                releasing = false;
            }
            Logger.log("summon-attach: landed at pos=" + Math.round(c.fallPos));
        } catch (Throwable t) {
            releasing = false;
            Logger.err("summon-attach: spawn failed", t);
        }
    }

    public static void drawCarried(Object entityObj, FakeGraphics g, P p, float siz) {
        if (active == 0) return;
        if (!(entityObj instanceof Entity) || g == null || p == null) return;
        try {
            Entity e = (Entity) entityObj;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.get(e.basis);
            if (rt == null) return;
            State st = rt.summonAttach;
            if (st.carries.isEmpty()) return;
            for (int i = 0; i < st.carries.size(); i++) {
                Carry c = st.carries.get(i);
                if (c == null || c.phase != 1 || c.summoner != e || c.ghost == null) continue;
                drawOnPart(c, g, p, siz);
            }
        } catch (Throwable t) {
            Logger.err("summon-attach: carried draw failed", t);
        }
    }

    private static void drawOnPart(Carry c, FakeGraphics g, P p, float siz) {
        float[] o = partOffset(c);
        if (o == null) return;
        FakeTransform old = g.getTransform();
        try {
            g.translate(p.x + (o[0] + c.cfg.offsetX) * siz, p.y + (o[1] + c.cfg.offsetY) * siz);
            if (Math.abs(o[4]) > 0.001f) g.rotate(o[4]);
            if (Math.abs(o[2] - 1f) > 0.001f || Math.abs(o[3] - 1f) > 0.001f) g.scale(o[2], o[3]);
            c.ghost.draw(g, new P(0f, 0f), siz * c.cfg.scale);
        } catch (Throwable ignored) {
        } finally {
            try {
                g.setTransform(old);
                g.delete(old);
            } catch (Throwable ignored) {}
        }
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics g) {
        if (rt == null || bbpainter == null || g == null) return;
        State st = rt.summonAttach;
        if (st.carries.isEmpty()) return;
        float siz = 1f;
        try { siz = BBPainterAccess.getSiz(bbpainter); } catch (Throwable ignored) {}
        FakeTransform old = ModeText.pushIdentity(g);
        try {
            for (int i = 0; i < st.carries.size(); i++) {
                Carry c = st.carries.get(i);
                if (c == null || c.phase != 2 || c.ghost == null) continue;
                float x = CrazyRender.screenX(bbpainter, c.fallPos);
                float y = CrazyRender.groundY(bbpainter, c.layer) - c.fallHeight * siz;
                c.ghost.draw(g, new P(x, y), siz * c.cfg.scale);
            }
        } catch (Throwable t) {
            Logger.err("summon-attach: fall draw failed", t);
        } finally {
            ModeText.popIdentity(g, old);
        }
    }

    private static float[] partOffset(Carry c) {
        EAnimU ea = liveAnim(c.summoner);
        if (ea == null || ea.f < 0f) return null;
        EPart[] parts = ea.ent;
        if (parts == null || c.cfg.part < 0 || c.cfg.part >= parts.length) return null;
        EPart part = parts[c.cfg.part];
        if (part == null) return null;
        try {
            AffineTransform at = AnimGeometry.partTransform(part, 1f);
            double m00 = at.getScaleX();
            double m10 = at.getShearY();
            double m01 = at.getShearX();
            double m11 = at.getScaleY();
            double rot = Math.atan2(m10, m00);
            double det = m00 * m11 - m01 * m10;
            double[] size = AnimGeometry.partSize(part);
            float sx = (float) size[0];
            float sy = (float) size[1];
            if (det < 0) sx = -sx;
            return new float[]{(float) at.getTranslateX(), (float) at.getTranslateY(),
                    sx, sy, (float) rot};
        } catch (Throwable t) {
            return null;
        }
    }

    private static EAnimU liveAnim(Entity e) {
        if (e == null) return null;
        try {
            Object am = BCUFields.get(e, "anim");
            if (am == null) return null;
            Object a = BCUFields.get(am, "anim");
            return (a instanceof EAnimU) ? (EAnimU) a : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static EAnimU buildGhost(Data.Proc.SUMMON proc, Cfg cfg) {
        try {
            if (proc == null || proc.id == null) return null;
            if (proc.id.cls != Unit.class) return null;
            Unit u = Identifier.getOr(proc.id, Unit.class);
            if (u == null || u.forms == null || u.forms.length == 0) return null;
            int fi = Math.max(0, Math.min(u.forms.length - 1, proc.form - 1));
            Form f = u.forms[fi];
            if (f == null) return null;
            AnimU.UType type = parseType(cfg.ghostAnim, AnimU.UType.WALK);
            EAnimU anim = f.getEAnim(type);
            if (anim != null) anim.setTime(0f);
            return anim;
        } catch (Throwable t) {
            Logger.err("summon-attach: ghost anim failed", t);
            return null;
        }
    }

    private static AnimU.UType parseType(String name, AnimU.UType fallback) {
        if (name == null) return fallback;
        try {
            return AnimU.UType.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static Data.Proc.SUMMON copyProc(Data.Proc.SUMMON src) {
        Data.Proc.SUMMON copy = Data.Proc.blank().SUMMON;
        copy.set(src);
        return copy;
    }

    private static int attackIndex(Object model) {
        Integer i = ATTACK_INDEX.get(model);
        return i == null ? -1 : i.intValue();
    }

    private static Cfg lookup(Entity summoner, int ind) {
        if (ind < 0) return null;
        Object data = summoner.data;
        Map<Integer, Cfg> perAtk = byData.get(data);
        if (perAtk != null) {
            Cfg c = perAtk.get(Integer.valueOf(ind));
            if (c != null) return c;
        }
        String k = deriveKey(data);
        return k == null ? null : byKey.get(k + "/" + ind);
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

    private static synchronized void ensureLoaded() {
        if (!byKey.isEmpty() || loadFailed) return;
        long now = System.currentTimeMillis();
        if (now - lastAttempt < RETRY_INTERVAL) return;
        lastAttempt = now;

        File file = configFile();
        if (!file.isFile()) {
            loadFailed = true;
            return;
        }

        Map<String, Cfg> raw;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, Cfg>>() {}.getType();
            raw = GSON.fromJson(reader, type);
        } catch (Throwable t) {
            Logger.err("summon-attach: failed reading " + file, t);
            return;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
        if (raw == null || raw.isEmpty()) {
            loadFailed = true;
            return;
        }

        Map<String, Cfg> kmap = new HashMap<String, Cfg>();
        Map<Object, Map<Integer, Cfg>> dmap = new IdentityHashMap<Object, Map<Integer, Cfg>>();
        for (Map.Entry<String, Cfg> e : raw.entrySet()) {
            Cfg cfg = e.getValue();
            if (cfg == null) continue;
            String key = e.getKey().trim();
            kmap.put(key, cfg);
            String[] parts = key.split("/");
            if (parts.length < 4) continue;
            Object owner = resolveOwner(parts);
            if (owner == null) continue;
            int ind;
            try { ind = Integer.parseInt(parts[3].trim()); } catch (Throwable t) { continue; }
            Map<Integer, Cfg> perAtk = dmap.get(owner);
            if (perAtk == null) {
                perAtk = new HashMap<Integer, Cfg>();
                dmap.put(owner, perAtk);
            }
            perAtk.put(Integer.valueOf(ind), cfg);
        }
        if (kmap.isEmpty()) {
            loadFailed = true;
            return;
        }
        byKey = kmap;
        byData = dmap;
        Logger.log("summon-attach: loaded " + kmap.size() + " entry(ies); keys=" + kmap.keySet());
    }

    private static Object resolveOwner(String[] parts) {
        try {
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

    public static File configFile() {
        String home = System.getProperty("manualcontrol.home");
        File homeFile = (home != null && !home.isEmpty()) ? new File(home, FILE_NAME) : null;
        File rootFile = new File(TileCatalog.bcuRoot(), FILE_NAME);
        if (homeFile != null && homeFile.isFile()) return homeFile;
        if (rootFile.isFile()) return rootFile;
        return homeFile != null ? homeFile : rootFile;
    }

    public static synchronized void invalidate() {
        byKey = Collections.emptyMap();
        byData = Collections.emptyMap();
        loadFailed = false;
        lastAttempt = 0L;
    }
}
