package manualcontrol.adventure;

import manualcontrol.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class AdventureSave {

    public static final int SLOT_COUNT = 3;
    private static final String SAVES_DIR = "adventure_saves";
    private static final String SAVE_FILE = "save.dat";
    private static final String BAK_FILE = "save.bak";
    private static final int VERSION = 2;
    private static boolean migratedLegacySaves;

    private AdventureSave() {}

    public static int slotCount() { return SLOT_COUNT; }

    private static File savesRoot() {
        File root = new File(patchFolder(), SAVES_DIR);
        migrateLegacySaves(root);
        return root;
    }

    private static File slotDir(int slot) {
        return new File(savesRoot(), "slot" + slot);
    }

    private static File saveFile(int slot) { return new File(slotDir(slot), SAVE_FILE); }
    private static File bakFile(int slot) { return new File(slotDir(slot), BAK_FILE); }

    public static boolean hasSlot(int slot) {
        return saveFile(slot).isFile() || bakFile(slot).isFile();
    }

    public static boolean writeSlot(int slot, AdventureSaveData d) {
        if (d == null) return false;
        try {
            File dir = slotDir(slot);
            if (!dir.exists() && !dir.mkdirs()) {
                Logger.err("Adventure: cannot create save dir " + dir, null);
                return false;
            }
            File save = saveFile(slot);
            if (save.isFile()) {
                try {
                    Files.copy(save.toPath(), bakFile(slot).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable t) {
                    Logger.err("Adventure: backup copy failed for slot " + slot, t);
                }
            }
            Properties p = toProperties(d);
            OutputStream out = new FileOutputStream(save);
            try {
                p.store(out, "BCU Adventure Mode save slot " + slot);
            } finally {
                out.close();
            }
            Logger.log("Adventure: saved slot=" + slot + " level=" + d.levelIndex
                    + " unit=" + d.unitLabel + " cores=" + d.coreTokens.size());
            return true;
        } catch (Throwable t) {
            Logger.err("Adventure: writeSlot " + slot + " failed", t);
            return false;
        }
    }

    public static AdventureSaveData readSlot(int slot) {
        AdventureSaveData d = readFile(saveFile(slot), slot);
        if (d != null) return d;
        d = readFile(bakFile(slot), slot);
        if (d != null) Logger.log("Adventure: slot " + slot + " loaded from backup");
        return d;
    }

    private static AdventureSaveData readFile(File f, int slot) {
        if (f == null || !f.isFile()) return null;
        try {
            Properties p = new Properties();
            InputStream in = new FileInputStream(f);
            try {
                p.load(in);
            } finally {
                in.close();
            }
            AdventureSaveData d = fromProperties(p);
            if (d == null) return null;
            d.slot = slot;
            return d;
        } catch (Throwable t) {
            Logger.err("Adventure: readFile " + f + " failed", t);
            return null;
        }
    }

    public static boolean deleteSlot(int slot) {
        boolean ok = true;
        File save = saveFile(slot), bak = bakFile(slot), dir = slotDir(slot);
        if (save.exists() && !save.delete()) ok = false;
        if (bak.exists() && !bak.delete()) ok = false;
        if (dir.exists() && dir.isDirectory()) dir.delete();
        Logger.log("Adventure: deleted slot=" + slot + " ok=" + ok);
        return ok;
    }

    private static Properties toProperties(AdventureSaveData d) {
        Properties p = new Properties();
        p.setProperty("version", String.valueOf(VERSION));
        p.setProperty("savedAt", String.valueOf(d.savedAt));
        p.setProperty("mode", d.mode == null ? AdventureSaveData.MODE_CASUAL : d.mode);
        p.setProperty("unitPack", d.unitPack == null ? "" : d.unitPack);
        p.setProperty("unitId", String.valueOf(d.unitId));
        p.setProperty("unitForm", String.valueOf(d.unitForm));
        p.setProperty("unitLabel", d.unitLabel == null ? "" : d.unitLabel);
        p.setProperty("levelIndex", String.valueOf(d.levelIndex));
        p.setProperty("stageName", d.stageName == null ? "" : d.stageName);
        p.setProperty("lives", String.valueOf(d.lives));
        p.setProperty("coinDmg", String.valueOf(d.coinDmg));
        p.setProperty("coinHp", String.valueOf(d.coinHp));
        p.setProperty("landingVfx", AdventureLandingVfx.fromId(d.landingVfx).id);
        p.setProperty("customMapId", d.customMapId == null ? "" : d.customMapId);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.coreTokens.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(d.coreTokens.get(i));
        }
        p.setProperty("cores", sb.toString());
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < d.seenCoreTokens.size(); i++) {
            if (i > 0) seen.append(',');
            seen.append(d.seenCoreTokens.get(i));
        }
        p.setProperty("seenCores", seen.toString());
        return p;
    }

    private static AdventureSaveData fromProperties(Properties p) {
        AdventureSaveData d = new AdventureSaveData();
        int version = parseInt(p.getProperty("version"), 1);
        d.savedAt = parseLong(p.getProperty("savedAt"), 0L);
        d.mode = p.getProperty("mode", AdventureSaveData.MODE_CASUAL);
        d.unitPack = p.getProperty("unitPack", "");
        d.unitId = parseInt(p.getProperty("unitId"), -1);
        d.unitForm = parseInt(p.getProperty("unitForm"), 0);
        d.unitLabel = p.getProperty("unitLabel", "");
        d.levelIndex = parseInt(p.getProperty("levelIndex"), 0);
        d.stageName = p.getProperty("stageName", "");
        d.lives = parseInt(p.getProperty("lives"), 1);
        d.coinDmg = parseFloat(p.getProperty("coinDmg"), 0f);
        d.coinHp = parseFloat(p.getProperty("coinHp"), 0f);
        d.landingVfx = AdventureLandingVfx.fromId(
                p.getProperty("landingVfx", AdventureLandingVfx.CRYSTAL.id)).id;
        d.customMapId = p.getProperty("customMapId", "");
        String cores = p.getProperty("cores", "");
        if (cores != null && !cores.trim().isEmpty()) {
            for (String tok : cores.split(",")) {
                String t = tok.trim();
                if (!t.isEmpty()) d.coreTokens.add(t);
            }
        }
        String seenCores = p.getProperty("seenCores", "");
        if (seenCores != null && !seenCores.trim().isEmpty()) {
            for (String tok : seenCores.split(",")) {
                String t = tok.trim();
                if (t.isEmpty()) continue;
                if (version < 2 && t.charAt(0) == 'F') {
                    int at = t.indexOf('@');
                    int ord = at > 1 ? parseInt(t.substring(at + 1), -1) : -1;
                    if (ord >= 0 && ord < AdventureCore.Tier.values().length) {
                        String uid = t.substring(0, at);
                        for (int i = 0; i <= ord; i++) {
                            String expanded = uid + "@" + i;
                            if (!d.seenCoreTokens.contains(expanded)) d.seenCoreTokens.add(expanded);
                        }
                        continue;
                    }
                }
                if (!d.seenCoreTokens.contains(t)) d.seenCoreTokens.add(t);
            }
        }

        if (d.seenCoreTokens.isEmpty()) d.seenCoreTokens.addAll(d.coreTokens);
        if (d.unitId < 0) return null;
        return d;
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return s == null ? def : Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static float parseFloat(String s, float def) {
        try { return s == null ? def : Float.parseFloat(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static File bcuFolder() {
        try {
            Class<?> cs = Class.forName("common.CommonStatic");
            Object ctx;
            try {
                ctx = cs.getField("ctx").get(null);
            } catch (NoSuchFieldException e) {
                Field cf = cs.getDeclaredField("ctx");
                cf.setAccessible(true);
                ctx = cf.get(null);
            }
            if (ctx != null) {
                Object f = ctx.getClass().getMethod("getBCUFolder").invoke(ctx);
                if (f instanceof File) return (File) f;
            }
        } catch (Throwable ignored) {}
        return new File(".");
    }

    private static File patchFolder() {
        String configured = System.getProperty("manualcontrol.home");
        if (configured != null && !configured.trim().isEmpty())
            return new File(configured.trim());
        return new File(bcuFolder(), "bcu-crazy-patch");
    }

    private static void migrateLegacySaves(File targetRoot) {
        if (migratedLegacySaves) return;
        migratedLegacySaves = true;
        try {
            File oldRoot = new File(bcuFolder(), SAVES_DIR);
            if (!oldRoot.isDirectory() || oldRoot.equals(targetRoot) || targetRoot.exists()) return;
            copyDir(oldRoot, targetRoot);
            Logger.log("Adventure: copied legacy saves to " + targetRoot);
        } catch (Throwable t) {
            Logger.err("Adventure: legacy save migration failed", t);
        }
    }

    private static void copyDir(File src, File dst) throws Exception {
        if (src == null || !src.exists()) return;
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs())
                throw new java.io.IOException("cannot create " + dst);
            File[] kids = src.listFiles();
            if (kids == null) return;
            for (File k : kids)
                copyDir(k, new File(dst, k.getName()));
        } else {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs())
                throw new java.io.IOException("cannot create " + parent);
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
