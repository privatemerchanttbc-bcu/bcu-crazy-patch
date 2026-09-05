package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.BackgroundAssetRef;
import manualcontrol.custommap.CustomMapDocument.BackgroundBand;
import manualcontrol.custommap.CustomMapDocument.BackgroundManifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class BackgroundLayoutEngine {

    static final class DrawCommand {
        final BackgroundAssetRef asset;
        final String band;
        final int order;
        final float x, y, width, height;
        final int alpha;

        DrawCommand(BackgroundAssetRef asset, String band, int order,
                    float x, float y, float width, float height, int alpha) {
            this.asset = asset;
            this.band = band;
            this.order = order;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.alpha = clamp(alpha, 0, 255);
        }

        float visibleLeft() {
            return x + asset.contentLeft * scaleX();
        }

        float visibleTop() {
            return y + asset.contentTop * scaleY();
        }

        float visibleWidth() {
            return asset.contentWidth() * scaleX();
        }

        float visibleHeight() {
            return asset.contentHeight() * scaleY();
        }

        private float scaleX() { return width / Math.max(1f, asset.width); }
        private float scaleY() { return height / Math.max(1f, asset.height); }
    }

    private BackgroundLayoutEngine() {}

    static List<DrawCommand> layout(BackgroundManifest manifest, int viewportWidth,
                                    int viewportHeight, float cameraPixels) {
        return layout(manifest, viewportWidth, viewportHeight, cameraPixels, 0f);
    }

    static List<DrawCommand> layout(BackgroundManifest manifest, int viewportWidth,
                                    int viewportHeight, float cameraX,
                                    float cameraY) {
        if (manifest == null || viewportWidth <= 0 || viewportHeight <= 0)
            return Collections.emptyList();
        ArrayList<BackgroundBand> bands = new ArrayList<BackgroundBand>();
        if (manifest.bands != null) for (BackgroundBand band : manifest.bands)
            if (band != null) bands.add(band);
        Collections.sort(bands, new Comparator<BackgroundBand>() {
            @Override public int compare(BackgroundBand a, BackgroundBand b) {
                int order = Integer.compare(a.order, b.order);
                return order != 0 ? order : safe(a.id).compareTo(safe(b.id));
            }
        });

        ArrayList<DrawCommand> out = new ArrayList<DrawCommand>();
        Rect stableFocalRect = stableFocalRect(manifest, bands, viewportWidth, viewportHeight);
        boolean celestialPlaced = false;
        boolean landmarkPlaced = false;
        for (BackgroundBand band : bands) {
            List<BackgroundAssetRef> assets = assets(manifest, band.role);
            if (assets.isEmpty()) continue;
            if ("sun".equals(band.role)) {

                if (celestialPlaced) continue;
                DrawCommand command = single(choose(assets, manifest.seed ^ band.seedSalt),
                        band, manifest.seed, viewportWidth, viewportHeight,
                        cameraX, cameraY);
                if (command != null) {
                    out.add(command);
                    celestialPlaced = true;
                }
            } else if (band.role != null && band.role.startsWith("landmark-")) {
                if (landmarkPlaced) continue;
                DrawCommand command = single(choose(assets, manifest.seed ^ band.seedSalt),
                        band, manifest.seed, viewportWidth, viewportHeight,
                        cameraX, cameraY);
                if (command != null) {
                    out.add(command);
                    landmarkPlaced = true;
                }
            } else if ("cover".equals(band.mode)) {
                addCover(out, choose(assets, manifest.seed ^ band.seedSalt), band,
                        viewportWidth, viewportHeight);
            } else if ("sky".equals(band.mode)) {
                addSky(out, choose(assets, manifest.seed ^ band.seedSalt), band,
                        viewportWidth, viewportHeight);
            } else if ("single".equals(band.mode)) {
                DrawCommand command = single(choose(assets, manifest.seed ^ band.seedSalt),
                        band, manifest.seed, viewportWidth, viewportHeight,
                        cameraX, cameraY);
                if (command != null) {
                    out.add(command);
                }
            } else if ("repeat".equals(band.mode)) {
                addRepeat(out, choose(assets, manifest.seed ^ band.seedSalt), band,
                        manifest.seed, viewportWidth, viewportHeight,
                        cameraX, cameraY);
            } else {
                addScatter(out, stableFocalRect, assets, band, manifest,
                        viewportWidth, viewportHeight, cameraX, cameraY);
            }
        }
        return out;
    }

    private static void addCover(List<DrawCommand> out, BackgroundAssetRef asset,
                                 BackgroundBand band, int width, int height) {
        float scale = Math.max(width / (float) Math.max(1, asset.width),
                height / (float) Math.max(1, asset.height));
        float drawW = asset.width * scale, drawH = asset.height * scale;
        out.add(new DrawCommand(asset, band.id, band.order,
                (width - drawW) * .5f, (height - drawH) * .5f,
                drawW, drawH, band.maxAlpha));
    }

    private static void addSky(List<DrawCommand> out, BackgroundAssetRef asset,
                               BackgroundBand band, int width, int height) {

        float scaleX = width / (float) Math.max(1, asset.contentWidth());
        float scaleY = height / (float) Math.max(1, asset.contentHeight());
        out.add(commandFromVisible(asset, band, 0f, 0f,
                scaleX, scaleY, band.maxAlpha));
    }

    private static DrawCommand single(BackgroundAssetRef asset, BackgroundBand band,
                                      long seed, int width, int height,
                                      float cameraX, float cameraY) {
        long key = BackgroundComposer.mix64(seed ^ band.seedSalt);
        float targetVisibleHeight = height * between(band.minSize, band.maxSize, random01(key));
        float scale = targetVisibleHeight / Math.max(1f, asset.contentHeight());
        float visibleW = asset.contentWidth() * scale;
        float leftFraction = .72f + .16f * random01(key + 0x41L);
        float visibleLeft = Math.min(width - visibleW * .92f, width * leftFraction)
                + cameraX * band.parallaxPercent / 100f;
        float visibleTop = height * between(band.minY, band.maxY,
                random01(key + 0x82L))
                + cameraY * band.parallaxPercent / 100f;
        return commandFromVisible(asset, band, visibleLeft, visibleTop, scale,
                alpha(band, key + 0xc3L));
    }

    private static void addRepeat(List<DrawCommand> out, BackgroundAssetRef asset,
                                  BackgroundBand band, long seed, int width, int height,
                                  float cameraX, float cameraY) {
        long key = BackgroundComposer.mix64(seed ^ band.seedSalt);
        float visibleHeight = height * between(band.minSize, band.maxSize, random01(key));
        float scaleY = visibleHeight / Math.max(1f, asset.contentHeight());
        float scaleX = scaleY * horizontalScale(band, key + 0x17L);

        float period = Math.max(1f, asset.contentWidth() * scaleX - 2f);
        float baseline = height * between(band.minY, band.maxY, random01(key + 0x31L));
        float scroll = cameraX * band.parallaxPercent / 100f;
        float verticalScroll = cameraY * band.parallaxPercent / 100f;
        float phase = random01(key + 0x62L) * period;
        int first = (int) Math.floor((-scroll - phase) / period) - 1;
        int last = (int) Math.ceil((width - scroll - phase) / period) + 1;
        int opacity = alpha(band, key + 0x93L);
        for (int index = first; index <= last; index++) {
            float visibleLeft = phase + index * period + scroll;
            float visibleTop = baseline - asset.contentHeight() * scaleY
                    + verticalScroll;
            out.add(commandFromVisible(asset, band, visibleLeft, visibleTop,
                    scaleX, scaleY, opacity));
        }
    }

    private static Rect stableFocalRect(BackgroundManifest manifest,
                                       List<BackgroundBand> bands,
                                       int width, int height) {
        Rect landmark = null;
        for (BackgroundBand band : bands) {
            if (!"sun".equals(band.role)
                    && (band.role == null || !band.role.startsWith("landmark-"))) continue;
            List<BackgroundAssetRef> assets = assets(manifest, band.role);
            if (assets.isEmpty()) continue;
            DrawCommand command = single(choose(assets, manifest.seed ^ band.seedSalt),
                    band, manifest.seed, width, height, 0f, 0f);
            if (command == null) continue;
            if ("sun".equals(band.role)) return rect(command);
            if (landmark == null) landmark = rect(command);
        }
        return landmark;
    }

    private static void addScatter(List<DrawCommand> out, Rect stableFocalRect,
                                   List<BackgroundAssetRef> assets, BackgroundBand band,
                                   BackgroundManifest manifest, int width, int height,
                                   float cameraX, float cameraY) {
        float complexity = clamp(manifest.complexity, 0, 100) / 100f;
        int target = Math.round(band.minCount + (band.maxCount - band.minCount) * complexity);
        if (target <= 0) return;
        long bandKey = BackgroundComposer.mix64(manifest.seed ^ band.seedSalt);
        if (band.maxCount > band.minCount && complexity > .05f && complexity < .95f) {
            int variation = random01(bandKey + 0x101L) < .34f ? -1
                    : random01(bandKey + 0x102L) > .72f ? 1 : 0;
            target = clamp(target + variation, band.minCount, band.maxCount);
        }
        if (target <= 0) return;

        float period = width / (float) target;
        float scroll = cameraX * band.parallaxPercent / 100f;
        float verticalScroll = cameraY * band.parallaxPercent / 100f;
        float phase = random01(bandKey + 0x201L) * period;
        float largest = maximumVisibleWidth(assets, band, width, height);
        int first = (int) Math.floor((-scroll - phase - largest) / period) - 1;
        int last = (int) Math.ceil((width - scroll - phase + largest) / period) + 1;
        for (int index = first; index <= last; index++) {
            long itemKey = BackgroundComposer.mix64(bandKey ^ (index * 0x9e3779b97f4a7c15L));
            BackgroundAssetRef asset = chooseForBand(assets, band, manifest.seed, index, itemKey);
            boolean cloud = band.role != null && band.role.startsWith("cloud-");
            float size = between(band.minSize, band.maxSize, random01(itemKey + 0x301L));
            float scaleY;
            float scaleX;
            if (cloud)
                scaleX = scaleY = width * size / Math.max(1f, asset.contentWidth());
            else {
                scaleY = height * size / Math.max(1f, asset.contentHeight());
                scaleX = scaleY * horizontalScale(band, itemKey + 0x305L);
            }

            float visibleW = asset.contentWidth() * scaleX;
            float jitterRoom = Math.max(0f, period - Math.min(period, visibleW));
            float center = phase + (index + .5f) * period + scroll
                    + (random01(itemKey + 0x302L) - .5f) * jitterRoom * .82f;
            float visibleLeft = center - visibleW * .5f;
            float visibleTop;
            if (cloud) {
                float verticalSample = random01(itemKey + 0x303L);
                if (band.maxY - band.minY >= .30f) {

                    float phaseY = random01(bandKey + 0x2f3L);
                    verticalSample = fraction(phaseY + index * .61803398875f);
                }
                visibleTop = height * between(band.minY, band.maxY, verticalSample);
            } else {
                float baseline = height * between(band.minY, band.maxY, random01(itemKey + 0x303L));
                visibleTop = baseline - asset.contentHeight() * scaleY;
            }
            visibleTop += verticalScroll;
            DrawCommand command = commandFromVisible(asset, band, visibleLeft, visibleTop,
                    scaleX, scaleY, alpha(band, itemKey + 0x304L));
            if (command.visibleLeft() > width + largest
                    || command.visibleLeft() + command.visibleWidth() < -largest) continue;

            if (cloud && stableFocalRect != null
                    && overlapRatio(rect(command), stableFocalRect) > .20f) {
                float alternateTop = command.visibleTop() < height * .22f
                        ? Math.min(height * .38f, command.visibleTop() + height * .12f)
                        : Math.max(height * .06f, command.visibleTop() - height * .12f);
                command = commandFromVisible(asset, band, visibleLeft, alternateTop,
                        scaleX, scaleY, command.alpha);
            }
            out.add(command);
        }
    }

    static float verticalContentSpan(BackgroundManifest manifest,
                                     int width, int height) {
        float min = 0f;
        float max = height;
        for (DrawCommand command : layout(manifest, width, height, 0f, 0f)) {
            min = Math.min(min, command.visibleTop());
            max = Math.max(max, command.visibleTop() + command.visibleHeight());
        }
        return Math.max(height, max - min) + height * .10f;
    }

    private static BackgroundAssetRef chooseForBand(List<BackgroundAssetRef> assets,
                                                    BackgroundBand band, long seed,
                                                    int index, long key) {
        if ("mid-mountain".equals(band.role)) {
            ArrayList<BackgroundAssetRef> green = filterPalette(assets, "green");
            ArrayList<BackgroundAssetRef> yellow = filterPalette(assets, "yellow");
            int yellowPhase = floorMod((int) (BackgroundComposer.mix64(seed ^ band.seedSalt) >>> 32), 5);
            if (!yellow.isEmpty() && floorMod(index - yellowPhase, 5) == 0)
                return chooseIndexed(yellow, index, key);
            if (!green.isEmpty()) return chooseIndexed(green, index, key);
        } else if ("near-mountain".equals(band.role)) {
            ArrayList<BackgroundAssetRef> dark = filterPalette(assets, "green-dark");
            if (!dark.isEmpty()) return chooseIndexed(dark, index, key);
            ArrayList<BackgroundAssetRef> green = filterPalette(assets, "green");
            if (!green.isEmpty()) return chooseIndexed(green, index, key);
        }
        return chooseIndexed(assets, index, key);
    }

    private static ArrayList<BackgroundAssetRef> filterPalette(List<BackgroundAssetRef> source,
                                                               String prefix) {
        ArrayList<BackgroundAssetRef> out = new ArrayList<BackgroundAssetRef>();
        for (BackgroundAssetRef asset : source)
            if (safe(asset.palette).startsWith(prefix)) out.add(asset);
        return out;
    }

    private static BackgroundAssetRef chooseIndexed(List<BackgroundAssetRef> assets,
                                                    int index, long seed) {
        if (assets.size() == 1) return assets.get(0);
        int phase = floorMod((int) (seed >>> 32), assets.size());
        return assets.get(floorMod(index + phase, assets.size()));
    }

    private static DrawCommand commandFromVisible(BackgroundAssetRef asset, BackgroundBand band,
                                                  float visibleLeft, float visibleTop,
                                                  float scale, int alpha) {
        return commandFromVisible(asset, band, visibleLeft, visibleTop, scale, scale, alpha);
    }

    private static DrawCommand commandFromVisible(BackgroundAssetRef asset, BackgroundBand band,
                                                  float visibleLeft, float visibleTop,
                                                  float scaleX, float scaleY, int alpha) {
        return new DrawCommand(asset, band.id, band.order,
                visibleLeft - asset.contentLeft * scaleX,
                visibleTop - asset.contentTop * scaleY,
                asset.width * scaleX, asset.height * scaleY, alpha);
    }

    private static float horizontalScale(BackgroundBand band, long seed) {
        float min = band.minHorizontalScale > 0f ? band.minHorizontalScale : 1f;
        float max = band.maxHorizontalScale > 0f ? band.maxHorizontalScale : min;
        if (max < min) {
            float swap = min;
            min = max;
            max = swap;
        }
        return between(min, max, random01(seed));
    }

    private static float maximumVisibleWidth(List<BackgroundAssetRef> assets,
                                             BackgroundBand band, int width, int height) {
        if (band.role != null && band.role.startsWith("cloud-"))
            return Math.max(1f, width * band.maxSize);
        float maxAspect = 1f;
        for (BackgroundAssetRef asset : assets)
            maxAspect = Math.max(maxAspect, asset.contentWidth()
                    / (float) Math.max(1, asset.contentHeight()));
        float maxHorizontal = band.maxHorizontalScale > 0f
                ? band.maxHorizontalScale : 1f;
        return Math.max(1f, height * band.maxSize * maxAspect * maxHorizontal);
    }

    private static List<BackgroundAssetRef> assets(BackgroundManifest manifest, String role) {
        ArrayList<BackgroundAssetRef> out = new ArrayList<BackgroundAssetRef>();
        if (manifest.assets != null) for (BackgroundAssetRef asset : manifest.assets)
            if (asset != null && role.equals(asset.role)) out.add(asset);
        return out;
    }

    private static BackgroundAssetRef choose(List<BackgroundAssetRef> assets, long seed) {
        return assets.get(floorMod((int) (BackgroundComposer.mix64(seed) >>> 32), assets.size()));
    }

    private static Rect rect(DrawCommand command) {
        return new Rect(command.visibleLeft(), command.visibleTop(),
                command.visibleWidth(), command.visibleHeight());
    }

    private static float overlapRatio(Rect a, Rect b) {
        float left = Math.max(a.x, b.x), top = Math.max(a.y, b.y);
        float right = Math.min(a.x + a.w, b.x + b.w);
        float bottom = Math.min(a.y + a.h, b.y + b.h);
        if (right <= left || bottom <= top) return 0f;
        float overlap = (right - left) * (bottom - top);
        return overlap / Math.max(1f, Math.min(a.w * a.h, b.w * b.h));
    }

    private static int alpha(BackgroundBand band, long seed) {
        return Math.round(between(band.minAlpha, band.maxAlpha, random01(seed)));
    }

    private static float between(float min, float max, float t) {
        return min + (max - min) * t;
    }

    private static float random01(long seed) {
        long bits = BackgroundComposer.mix64(seed) >>> 11;
        return (float) (bits * 0x1.0p-53);
    }

    private static float fraction(float value) {
        return value - (float) Math.floor(value);
    }

    private static int floorMod(int value, int divisor) {
        int result = value % divisor;
        return result < 0 ? result + divisor : result;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Rect {
        final float x, y, w, h;
        Rect(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }
}
