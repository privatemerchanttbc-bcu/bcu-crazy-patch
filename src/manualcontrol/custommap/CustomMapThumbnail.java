package manualcontrol.custommap;

import common.pack.Identifier;
import common.util.pack.Background;
import manualcontrol.Logger;
import manualcontrol.custommap.CustomMapDocument.ModeVariant;
import manualcontrol.custommap.CustomMapDocument.TreePlacement;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.ImageIcon;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CustomMapThumbnail {

    private static final int CACHE_LIMIT = 16;
    private static final int MAX_TREE_ASSETS = 256;
    private static final ImageIcon MISS = new ImageIcon();
    private static final Map<String, ImageIcon> CACHE =
            new LinkedHashMap<String, ImageIcon>();

    private CustomMapThumbnail() {}

    static ImageIcon icon(Identifier<Background> id, int width, int height) {
        if (id == null || width <= 0 || height <= 0) return null;
        if (!CustomMapRepository.PACK_ID.equals(id.pack)) return null;
        String key = id.pack + ":" + id.id + "@" + width + "x" + height;
        synchronized (CACHE) {
            ImageIcon cached = CACHE.get(key);
            if (cached != null) return cached == MISS ? null : cached;
        }
        ImageIcon icon = MISS;
        try {
            CustomMapDocument doc = CustomMapRepository.documentForBackground(id);
            BufferedImage image = doc == null ? null : render(doc, width, height);
            if (image != null) icon = new ImageIcon(image);
        } catch (Throwable t) {
            Logger.err("CustomMap: Background preview render failed", t);
        }
        synchronized (CACHE) {
            while (CACHE.size() >= CACHE_LIMIT) {
                Iterator<String> stale = CACHE.keySet().iterator();
                if (!stale.hasNext()) break;
                stale.next();
                stale.remove();
            }
            CACHE.put(key, icon);
        }
        return icon == MISS ? null : icon;
    }

    static void invalidate(String uuid) {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    static BufferedImage render(CustomMapDocument doc, int width, int height) {
        if (doc == null || doc.uuid == null || width <= 0 || height <= 0) return null;
        BufferedImage canvas = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            drawSky(g, doc, width, height);
            drawTerrain(g, doc, width, height);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private static void drawSky(Graphics2D g, CustomMapDocument doc,
                                int width, int height) {
        int top = doc.backgroundManifest == null
                ? 0xff53d7ef : doc.backgroundManifest.skyTopArgb;
        int bottom = doc.backgroundManifest == null
                ? 0xffc8f4f7 : doc.backgroundManifest.skyBottomArgb;
        g.setPaint(new GradientPaint(0, 0, new Color(top, true),
                0, height, new Color(bottom, true)));
        g.fillRect(0, 0, width, height);
        if (doc.backgroundManifest == null) return;
        Composite previous = g.getComposite();
        HashMap<String, BufferedImage> decoded = new HashMap<String, BufferedImage>();
        for (BackgroundLayoutEngine.DrawCommand command
                : BackgroundLayoutEngine.layout(doc.backgroundManifest,
                width, height, 0f)) {
            if (command.asset == null || command.asset.asset == null
                    || command.asset.asset.isEmpty()) continue;
            String path = "custom_maps/" + doc.uuid + "/" + command.asset.asset;
            BufferedImage image;
            if (decoded.containsKey(path)) image = decoded.get(path);
            else {
                image = read(path, Math.round(command.width));
                decoded.put(path, image);
            }
            if (image == null) continue;
            if (command.alpha < 255)
                g.setComposite(AlphaComposite.SrcOver.derive(command.alpha / 255f));
            g.drawImage(image, Math.round(command.x), Math.round(command.y),
                    Math.max(1, Math.round(command.width)),
                    Math.max(1, Math.round(command.height)), null);
            g.setComposite(previous);
        }
        g.setComposite(previous);
    }

    private static void drawTerrain(Graphics2D g, CustomMapDocument doc,
                                    int width, int height) {
        for (Candidate candidate : candidates(doc))
            if (drawVariant(g, doc, candidate, width, height)) return;
    }

    private static boolean drawVariant(Graphics2D g, CustomMapDocument doc,
                                       Candidate candidate, int width, int height) {
        ModeVariant variant = candidate.variant;
        if (variant == null || variant.width <= 0 || variant.height <= 0) return false;
        float cell = Math.min(width / (float) variant.width,
                height / (float) variant.height);
        if (cell <= 0f) return false;
        String root = "custom_maps/" + doc.uuid + "/" + candidate.id + "/chunks/";
        int chunk = chunkTiles(root, variant);
        if (chunk <= 0) return false;
        int chunksX = (variant.width + chunk - 1) / chunk;
        int chunksY = (variant.height + chunk - 1) / chunk;
        float originX = (width - variant.width * cell) * .5f;
        float originY = height - variant.height * cell;
        drawTrees(g, doc, variant, originX, originY, cell);
        drawChunkLayer(g, root + "under/", chunk, chunksX, chunksY, originX, originY, cell);
        drawChunkLayer(g, root + "over/", chunk, chunksX, chunksY, originX, originY, cell);
        return true;
    }

    private static int chunkTiles(String root, ModeVariant variant) {
        int fine = CustomMapDocument.CHUNK_TILES;
        int scanX = (variant.width + fine - 1) / fine;
        int scanY = (variant.height + fine - 1) / fine;
        int maxCx = -1;
        int maxCy = -1;
        for (int cy = 0; cy < scanY; cy++)
            for (int cx = 0; cx < scanX; cx++)
                if (exists(root + "under/" + cx + "_" + cy + ".png")) {
                    maxCx = Math.max(maxCx, cx);
                    maxCy = Math.max(maxCy, cy);
                }
        if (maxCx < 0) return 0;
        for (int candidate = fine; candidate <= fine * 4; candidate *= 2)
            if ((variant.width + candidate - 1) / candidate == maxCx + 1
                    && (variant.height + candidate - 1) / candidate == maxCy + 1)
                return candidate;
        return fine;
    }

    private static boolean exists(String path) {
        InputStream in = null;
        try {
            in = CustomMapRepository.stream(path);
            return in != null;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void drawChunkLayer(Graphics2D g, String root, int chunk,
                                       int chunksX, int chunksY, float originX,
                                       float originY, float cell) {
        int span = Math.max(1, Math.round(chunk * cell));
        for (int cy = 0; cy < chunksY; cy++)
            for (int cx = 0; cx < chunksX; cx++) {
                BufferedImage image = read(root + cx + "_" + cy + ".png", span);
                if (image == null) continue;
                g.drawImage(image,
                        Math.round(originX + cx * chunk * cell),
                        Math.round(originY + cy * chunk * cell),
                        span, span, null);
            }
    }

    private static void drawTrees(Graphics2D g, CustomMapDocument doc,
                                  ModeVariant variant, float originX,
                                  float originY, float cell) {
        if (variant.trees == null || variant.trees.isEmpty()) return;
        List<BufferedImage> assets = treeAssets(doc);
        if (assets.isEmpty()) return;
        boolean volcanoScale = doc.themeProfile != null && doc.themeProfile.isLava();
        BufferedImage ground = volcanoScale
                ? read("custom_maps/" + doc.uuid + "/assets/ground/000.png") : null;
        float volcanoUnit = ground == null ? 16f
                : Math.max(1f, Math.min(ground.getWidth(), ground.getHeight()));
        int[] opaqueBottoms = new int[assets.size()];
        for (int i = 0; i < assets.size(); i++)
            opaqueBottoms[i] = alphaContentBottom(assets.get(i));
        float contact = CustomMapPreviewPanel.treeRootContactRatio(variant);
        for (TreePlacement placement : variant.trees) {
            if (placement == null) continue;
            int index = Math.floorMod(placement.asset, assets.size());
            BufferedImage image = assets.get(index);
            float unit = volcanoScale ? volcanoUnit : Math.max(1f, image.getWidth());
            float centerX = originX + (placement.x + .5f
                    + placement.xOffsetPercent / 100f) * cell;
            float bottom = originY + (placement.y + contact) * cell;
            float scale = CustomMapPreviewPanel.treeAssetScale(unit,
                    image.getWidth(), image.getHeight(), placement.scalePercent);
            float w = image.getWidth() * scale * cell / unit;
            float h = image.getHeight() * scale * cell / unit;
            float opaqueBottom = opaqueBottoms[index] * scale * cell / unit;
            g.drawImage(image, Math.round(centerX - w / 2f),
                    Math.round(bottom - opaqueBottom),
                    Math.max(1, Math.round(w)), Math.max(1, Math.round(h)), null);
        }
    }

    private static List<BufferedImage> treeAssets(CustomMapDocument doc) {
        ArrayList<BufferedImage> out = new ArrayList<BufferedImage>();
        for (int i = 0; i < MAX_TREE_ASSETS; i++) {
            BufferedImage image = read("custom_maps/" + doc.uuid + "/assets/tree/"
                    + String.format(Locale.ROOT, "%03d.png", i));
            if (image == null) break;
            out.add(image);
        }
        return out;
    }

    private static int alphaContentBottom(BufferedImage image) {
        for (int y = image.getHeight() - 1; y >= 0; y--)
            for (int x = 0; x < image.getWidth(); x++)
                if (((image.getRGB(x, y) >>> 24) & 0xff) >= 16) return y + 1;
        return image.getHeight();
    }

    private static List<Candidate> candidates(CustomMapDocument doc) {
        ArrayList<Candidate> out = new ArrayList<Candidate>();
        if (doc.battleTerrain != null)
            out.add(new Candidate("battle", doc.battleTerrain));
        if (doc.variants != null)
            for (Map.Entry<String, ModeVariant> entry : doc.variants.entrySet())
                if (entry.getKey() != null && entry.getValue() != null
                        && !"battle".equals(entry.getKey()))
                    out.add(new Candidate(entry.getKey(), entry.getValue()));
        return out;
    }

    private static BufferedImage read(String path) {
        return read(path, 0);
    }

    private static BufferedImage read(String path, int targetWidth) {
        InputStream in = null;
        ImageInputStream stream = null;
        ImageReader reader = null;
        try {
            in = CustomMapRepository.stream(path);
            if (in == null) return null;
            stream = ImageIO.createImageInputStream(in);
            if (stream == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            reader = readers.next();
            reader.setInput(stream, true, true);
            ImageReadParam param = reader.getDefaultReadParam();
            int step = targetWidth <= 0 ? 1
                    : Math.max(1, reader.getWidth(0) / Math.max(1, targetWidth));
            if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
            return reader.read(0, param);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (reader != null) reader.dispose();
            if (stream != null) try { stream.close(); } catch (Throwable ignored) {}
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static final class Candidate {
        final String id;
        final ModeVariant variant;

        Candidate(String id, ModeVariant variant) {
            this.id = id;
            this.variant = variant;
        }
    }
}
