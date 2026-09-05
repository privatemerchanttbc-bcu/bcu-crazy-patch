package manualcontrol.custommap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TerrainHeightfield {

    public interface SupportAvailability {
        boolean isAvailable(CustomMapDocument.ModeVariant terrain,
                            CustomMapDocument.SecondaryPlatform platform,
                            int tileX);
    }

    public static final SupportAvailability ALLOW_ALL = new SupportAvailability() {
        @Override
        public boolean isAvailable(CustomMapDocument.ModeVariant terrain,
                                   CustomMapDocument.SecondaryPlatform platform,
                                   int tileX) {
            return true;
        }
    };

    public static final class Contact {
        public final CustomMapRuntime.TerrainKind kind;
        public final float supportLayer;
        public final boolean inBounds;
        public final byte surfaceMaterial;
        public final String material;
        public final CustomMapDocument.SecondaryPlatform platform;
        public final String platformId;
        public final MovingPlatformEngine.Pose platformPose;
        public final int mainTileX;
        public final int platformLocalX;

        Contact(CustomMapRuntime.TerrainKind kind, float supportLayer,
                boolean inBounds, CustomMapDocument.SecondaryPlatform platform,
                MovingPlatformEngine.Pose platformPose, byte surfaceMaterial) {
            this(kind, supportLayer, inBounds, platform, platformPose,
                    surfaceMaterial, -1, -1, null);
        }

        Contact(CustomMapRuntime.TerrainKind kind, float supportLayer,
                boolean inBounds, CustomMapDocument.SecondaryPlatform platform,
                MovingPlatformEngine.Pose platformPose, byte surfaceMaterial,
                int mainTileX, int platformLocalX) {
            this.kind = kind;
            this.supportLayer = supportLayer;
            this.inBounds = inBounds;
            this.surfaceMaterial = surfaceMaterial == CustomMapDocument.SURFACE_ICE
                    ? CustomMapDocument.SURFACE_ICE
                    : CustomMapDocument.SURFACE_NORMAL;
            this.platform = platform;
            this.platformId = platform == null ? null : platform.id;
            this.platformPose = platformPose;
            this.mainTileX = mainTileX;
            this.platformLocalX = platformLocalX;
            this.material = materialFor(kind, this.surfaceMaterial, null);
        }

        Contact(CustomMapRuntime.TerrainKind kind, float supportLayer,
                boolean inBounds, CustomMapDocument.SecondaryPlatform platform,
                MovingPlatformEngine.Pose platformPose, byte surfaceMaterial,
                int mainTileX, int platformLocalX, String material) {
            this.kind = kind;
            this.supportLayer = supportLayer;
            this.inBounds = inBounds;
            this.surfaceMaterial = surfaceMaterial == CustomMapDocument.SURFACE_ICE
                    ? CustomMapDocument.SURFACE_ICE
                    : CustomMapDocument.SURFACE_NORMAL;
            this.platform = platform;
            this.platformId = platform == null ? null : platform.id;
            this.platformPose = platformPose;
            this.mainTileX = mainTileX;
            this.platformLocalX = platformLocalX;
            this.material = materialFor(kind, this.surfaceMaterial, material);
        }

        private static String materialFor(CustomMapRuntime.TerrainKind kind,
                                          byte surface, String explicit) {
            if (explicit != null && !explicit.trim().isEmpty()) return explicit;
            if (kind == CustomMapRuntime.TerrainKind.WATER)
                return CustomMapDocument.MATERIAL_WATER;
            return surface == CustomMapDocument.SURFACE_ICE
                    ? CustomMapDocument.MATERIAL_ICE
                    : CustomMapDocument.MATERIAL_NORMAL;
        }

        Contact(CustomMapRuntime.TerrainKind kind, float supportLayer,
                boolean inBounds, CustomMapDocument.SecondaryPlatform platform) {
            this(kind, supportLayer, inBounds, platform, null,
                    platform == null ? CustomMapDocument.SURFACE_NORMAL
                            : platform.surfaceMaterial);
        }

        public boolean grounded() {
            return kind == CustomMapRuntime.TerrainKind.MAIN
                    || kind == CustomMapRuntime.TerrainKind.FLOATING;
        }
    }

    public static final class Sweep {
        public final float worldX;
        public final Contact contact;
        public final boolean blocked;

        Sweep(float worldX, Contact contact, boolean blocked) {
            this.worldX = worldX;
            this.contact = contact;
            this.blocked = blocked;
        }
    }

    public static final class AirborneContact {
        public final float worldX;
        public final float actorLayer;
        public final float fraction;
        public final Contact contact;

        AirborneContact(float worldX, float actorLayer, float fraction,
                        Contact contact) {
            this.worldX = worldX;
            this.actorLayer = actorLayer;
            this.fraction = clamp(fraction, 0f, 1f);
            this.contact = contact;
        }

        public boolean hit() {
            return contact != null
                    && contact.kind != CustomMapRuntime.TerrainKind.VOID;
        }
    }

    public enum StepKind {
        UP, DOWN
    }

    public static final class MainStep {
        public final StepKind kind;
        public final int fromTile;
        public final int toTile;
        public final int direction;
        public final float boundaryWorldX;
        public final float fromLayer;
        public final float toLayer;

        MainStep(StepKind kind, int fromTile, int toTile, int direction,
                 float boundaryWorldX, float fromLayer, float toLayer) {
            this.kind = kind;
            this.fromTile = fromTile;
            this.toTile = toTile;
            this.direction = direction;
            this.boundaryWorldX = boundaryWorldX;
            this.fromLayer = fromLayer;
            this.toLayer = toLayer;
        }

        public int heightRows(CustomMapDocument.ModeVariant terrain) {
            return terrain == null ? 0 : Math.max(1, Math.round(
                    Math.abs(toLayer - fromLayer)
                            / Math.max(1f, terrain.layerUnitsPerTile())));
        }
    }

    private TerrainHeightfield() {}

    public static Contact sample(CustomMapDocument.ModeVariant terrain,
                                 float worldX, float actorLayer,
                                 boolean falling, boolean includeSecondary) {
        return sample(terrain, worldX, actorLayer, falling, includeSecondary, 0L);
    }

    public static Contact sample(CustomMapDocument.ModeVariant terrain,
                                 float worldX, float actorLayer,
                                 boolean falling, boolean includeSecondary,
                                 long platformTick) {
        return sample(terrain, worldX, actorLayer, falling, includeSecondary,
                platformTick, ALLOW_ALL);
    }

    public static Contact sample(CustomMapDocument.ModeVariant terrain,
                                 float worldX, float actorLayer,
                                 boolean falling, boolean includeSecondary,
                                 long platformTick,
                                 SupportAvailability availability) {
        availability = availability == null ? ALLOW_ALL : availability;
        if (terrain == null || !terrain.containsWorldX(worldX))
            return new Contact(CustomMapRuntime.TerrainKind.VOID,
                    Float.NaN, false, null);
        int tile = tileAt(terrain, worldX);

        if (includeSecondary) {
            PlatformSupport support = secondarySupport(
                    terrain, worldX, actorLayer, falling, platformTick,
                    availability);
            if (support != null)
                return new Contact(CustomMapRuntime.TerrainKind.FLOATING,
                        support.supportLayer, true,
                        support.platform, support.pose,
                        support.platform.surfaceMaterial,
                        -1, support.localTileX);
        }

        if (terrain.water != null && terrain.water[tile])
            return new Contact(CustomMapRuntime.TerrainKind.WATER,
                    waterLayer(terrain, tile), true, null, null,
                    CustomMapDocument.SURFACE_NORMAL, -1, -1,
                    terrain.liquidMaterialAt(worldX));
        float main = terrain.surfaceLayerAt(worldX);
        if (!Float.isNaN(main)
                && availability.isAvailable(terrain, null, tile))
            return new Contact(CustomMapRuntime.TerrainKind.MAIN, main, true,
                    null, null, terrain.surfaceMaterialAt(worldX), tile, -1,
                    terrain.surface != null && tile < terrain.surface.length
                            ? terrain.materialAt(tile, terrain.surface[tile]) : null);
        return new Contact(CustomMapRuntime.TerrainKind.VOID,
                Float.NaN, true, null);
    }

    public static Contact sweepLanding(CustomMapDocument.ModeVariant terrain,
                                       float fromWorldX, float fromLayer,
                                       float toWorldX, float toLayer,
                                       long platformTick,
                                       boolean includeSecondary) {
        return sweepLanding(terrain, fromWorldX, fromLayer, toWorldX, toLayer,
                platformTick, includeSecondary, ALLOW_ALL);
    }

    public static Contact sweepLanding(CustomMapDocument.ModeVariant terrain,
                                       float fromWorldX, float fromLayer,
                                       float toWorldX, float toLayer,
                                       long platformTick,
                                       boolean includeSecondary,
                                       SupportAvailability availability) {
        availability = availability == null ? ALLOW_ALL : availability;
        if (terrain == null) return sample(null, toWorldX, toLayer,
                true, includeSecondary, platformTick, availability);
        float dx = toWorldX - fromWorldX;
        float dy = toLayer - fromLayer;
        List<PlatformSweep> platformSweeps = includeSecondary
                ? platformSweeps(terrain, platformTick)
                : Collections.<PlatformSweep>emptyList();
        int xSteps = (int) Math.ceil(Math.abs(dx)
                / Math.max(1f, terrain.worldUnitsPerTile() / 8f));
        int ySteps = (int) Math.ceil(Math.abs(dy)
                / Math.max(1f, terrain.layerUnitsPerTile() / 4f));
        int steps = Math.max(1, Math.max(xSteps, ySteps));

        for (PlatformSweep sweep : platformSweeps) {
            float platformDx = (sweep.currentCenterTileX
                    - sweep.previousCenterTileX) * terrain.worldUnitsPerTile();
            float platformDy = sweep.currentSupportLayer
                    - sweep.previousSupportLayer;
            int relativeXSteps = (int) Math.ceil(Math.abs(dx - platformDx)
                    / Math.max(1f, terrain.worldUnitsPerTile() / 8f));
            int relativeYSteps = (int) Math.ceil(Math.abs(dy - platformDy)
                    / Math.max(1f, terrain.layerUnitsPerTile() / 4f));
            steps = Math.max(steps, Math.max(relativeXSteps, relativeYSteps));
        }
        float tolerance = terrain.layerUnitsPerTile() * .10f;
        for (int i = 1; i <= steps; i++) {
            float priorT = (i - 1) / (float) steps;
            float t = i / (float) steps;
            float priorX = fromWorldX + dx * priorT;
            float priorLayer = fromLayer + dy * priorT;
            float x = fromWorldX + dx * t;
            float layer = fromLayer + dy * t;

            if (includeSecondary && dy >= 0f) {
                PlatformLanding landing = secondaryLanding(platformSweeps,
                        terrain, priorX, priorLayer, x, layer,
                        priorT, t, tolerance, availability);
                if (landing != null)
                    return new Contact(CustomMapRuntime.TerrainKind.FLOATING,
                            landing.supportLayer, true,
                            landing.sweep.platform, landing.sweep.currentPose,
                            landing.sweep.platform.surfaceMaterial,
                            -1, landing.localTileX);
            }

            Contact contact = sample(terrain, x, layer,
                    true, false, platformTick, availability);
            if (contact.grounded() && dy >= 0f) {
                if (priorLayer <= contact.supportLayer + tolerance
                        && layer >= contact.supportLayer - tolerance) return contact;
            }
        }
        return new Contact(CustomMapRuntime.TerrainKind.VOID,
                Float.NaN, terrain.containsWorldX(toWorldX), null);
    }

    public static AirborneContact sweepAirborne(
            CustomMapDocument.ModeVariant terrain,
            float fromWorldX, float fromLayer,
            float toWorldX, float toLayer,
            long platformTick, boolean includeSecondary) {
        return sweepAirborne(terrain, fromWorldX, fromLayer,
                toWorldX, toLayer, platformTick, includeSecondary, ALLOW_ALL);
    }

    public static AirborneContact sweepAirborne(
            CustomMapDocument.ModeVariant terrain,
            float fromWorldX, float fromLayer,
            float toWorldX, float toLayer,
            long platformTick, boolean includeSecondary,
            SupportAvailability availability) {
        availability = availability == null ? ALLOW_ALL : availability;
        if (terrain == null) return noAirborneHit(
                terrain, toWorldX, toLayer, includeSecondary, platformTick,
                availability);

        float dx = toWorldX - fromWorldX;
        float dy = toLayer - fromLayer;
        List<PlatformSweep> platformSweeps = includeSecondary
                ? platformSweeps(terrain, platformTick)
                : Collections.<PlatformSweep>emptyList();
        int xSteps = (int) Math.ceil(Math.abs(dx)
                / Math.max(1f, terrain.worldUnitsPerTile() / 8f));
        int ySteps = (int) Math.ceil(Math.abs(dy)
                / Math.max(1f, terrain.layerUnitsPerTile() / 4f));
        int steps = Math.max(1, Math.max(xSteps, ySteps));
        for (PlatformSweep sweep : platformSweeps) {
            float platformDx = (sweep.currentCenterTileX
                    - sweep.previousCenterTileX) * terrain.worldUnitsPerTile();
            float platformDy = sweep.currentSupportLayer
                    - sweep.previousSupportLayer;
            int relativeXSteps = (int) Math.ceil(Math.abs(dx - platformDx)
                    / Math.max(1f, terrain.worldUnitsPerTile() / 8f));
            int relativeYSteps = (int) Math.ceil(Math.abs(dy - platformDy)
                    / Math.max(1f, terrain.layerUnitsPerTile() / 4f));
            steps = Math.max(steps, Math.max(relativeXSteps, relativeYSteps));
        }

        Contact start = sample(terrain, fromWorldX, fromLayer,
                true, false, platformTick, availability);
        if (start.kind == CustomMapRuntime.TerrainKind.WATER
                && !Float.isNaN(start.supportLayer)
                && fromLayer >= start.supportLayer)
            return new AirborneContact(fromWorldX, fromLayer, 0f, start);

        float tolerance = terrain.layerUnitsPerTile() * .10f;
        for (int i = 1; i <= steps; i++) {
            float priorT = (i - 1) / (float) steps;
            float t = i / (float) steps;
            float priorX = lerp(fromWorldX, toWorldX, priorT);
            float priorLayer = lerp(fromLayer, toLayer, priorT);
            float x = lerp(fromWorldX, toWorldX, t);
            float layer = lerp(fromLayer, toLayer, t);
            AirborneContact best = null;

            if (includeSecondary && dy >= 0f) {
                PlatformLanding landing = secondaryLanding(platformSweeps,
                        terrain, priorX, priorLayer, x, layer,
                        priorT, t, tolerance, availability);
                if (landing != null) {
                    Contact contact = new Contact(
                            CustomMapRuntime.TerrainKind.FLOATING,
                            landing.supportLayer, true,
                            landing.sweep.platform, landing.sweep.currentPose,
                            landing.sweep.platform.surfaceMaterial,
                            -1, landing.localTileX);
                    best = new AirborneContact(
                            lerp(fromWorldX, toWorldX, landing.fraction),
                            lerp(fromLayer, toLayer, landing.fraction),
                            landing.fraction, contact);
                }
            }

            if (dy >= 0f) {
                AirborneContact main = mainLanding(terrain,
                        fromWorldX, fromLayer, toWorldX, toLayer,
                        priorT, t, platformTick, tolerance, availability);
                best = earlierAirborne(best, main);
            }
            AirborneContact water = waterContact(terrain,
                    fromWorldX, fromLayer, toWorldX, toLayer,
                    priorT, t, platformTick, availability);
            best = earlierAirborne(best, water);
            if (best != null) return best;
        }
        return noAirborneHit(terrain, toWorldX, toLayer,
                includeSecondary, platformTick, availability);
    }

    private static AirborneContact mainLanding(
            CustomMapDocument.ModeVariant terrain,
            float fromWorldX, float fromLayer,
            float toWorldX, float toLayer,
            float t0, float t1, long platformTick, float tolerance,
            SupportAvailability availability) {
        float x0 = lerp(fromWorldX, toWorldX, t0);
        float x1 = lerp(fromWorldX, toWorldX, t1);
        float y0 = lerp(fromLayer, toLayer, t0);
        float y1 = lerp(fromLayer, toLayer, t1);
        Contact c0 = sample(terrain, x0, y0,
                true, false, platformTick, availability);
        Contact c1 = sample(terrain, x1, y1,
                true, false, platformTick, availability);
        if (c1.kind != CustomMapRuntime.TerrainKind.MAIN) return null;
        float support0 = c0.kind == CustomMapRuntime.TerrainKind.MAIN
                ? c0.supportLayer : c1.supportLayer;
        float relative0 = y0 - support0;
        float relative1 = y1 - c1.supportLayer;
        float relativeDelta = relative1 - relative0;
        if (relative0 > tolerance || relative1 < -tolerance
                || relativeDelta < -0.0001f) return null;
        float alpha = relativeDelta > 0.0001f
                ? clamp(-relative0 / relativeDelta, 0f, 1f) : 1f;
        float fraction = lerp(t0, t1, alpha);
        float x = lerp(fromWorldX, toWorldX, fraction);
        float y = lerp(fromLayer, toLayer, fraction);
        Contact contact = sample(terrain, x, y,
                true, false, platformTick, availability);
        if (contact.kind != CustomMapRuntime.TerrainKind.MAIN) contact = c1;
        return new AirborneContact(x, y, fraction, contact);
    }

    private static AirborneContact waterContact(
            CustomMapDocument.ModeVariant terrain,
            float fromWorldX, float fromLayer,
            float toWorldX, float toLayer,
            float t0, float t1, long platformTick,
            SupportAvailability availability) {
        float x0 = lerp(fromWorldX, toWorldX, t0);
        float x1 = lerp(fromWorldX, toWorldX, t1);
        float y0 = lerp(fromLayer, toLayer, t0);
        float y1 = lerp(fromLayer, toLayer, t1);
        Contact c0 = sample(terrain, x0, y0,
                true, false, platformTick, availability);
        Contact c1 = sample(terrain, x1, y1,
                true, false, platformTick, availability);
        boolean wet0 = c0.kind == CustomMapRuntime.TerrainKind.WATER;
        boolean wet1 = c1.kind == CustomMapRuntime.TerrainKind.WATER;
        if (!wet0 && !wet1) return null;

        float wetStart = t0;
        float wetEnd = t1;
        Contact startContact = c0;
        Contact endContact = c1;
        if (!wet0) {
            float lo = t0;
            float hi = t1;
            for (int n = 0; n < 14; n++) {
                float mid = (lo + hi) * .5f;
                Contact probe = sample(terrain,
                        lerp(fromWorldX, toWorldX, mid),
                        lerp(fromLayer, toLayer, mid),
                        true, false, platformTick, availability);
                if (probe.kind == CustomMapRuntime.TerrainKind.WATER) {
                    hi = mid;
                    startContact = probe;
                } else lo = mid;
            }
            wetStart = hi;
        } else if (!wet1) {
            float lo = t0;
            float hi = t1;
            for (int n = 0; n < 14; n++) {
                float mid = (lo + hi) * .5f;
                Contact probe = sample(terrain,
                        lerp(fromWorldX, toWorldX, mid),
                        lerp(fromLayer, toLayer, mid),
                        true, false, platformTick, availability);
                if (probe.kind == CustomMapRuntime.TerrainKind.WATER) {
                    lo = mid;
                    endContact = probe;
                } else hi = mid;
            }
            wetEnd = lo;
        }
        if (startContact.kind != CustomMapRuntime.TerrainKind.WATER)
            startContact = c1;
        if (endContact.kind != CustomMapRuntime.TerrainKind.WATER)
            endContact = c0;

        float startLayer = lerp(fromLayer, toLayer, wetStart);
        float endLayer = lerp(fromLayer, toLayer, wetEnd);
        float startRelative = startLayer - startContact.supportLayer;
        float endRelative = endLayer - endContact.supportLayer;
        if (startRelative >= 0f)
            return new AirborneContact(
                    lerp(fromWorldX, toWorldX, wetStart),
                    startLayer, wetStart, startContact);
        if (endRelative < 0f) return null;
        float relativeDelta = endRelative - startRelative;
        if (relativeDelta <= 0.0001f) return null;
        float alpha = clamp(-startRelative / relativeDelta, 0f, 1f);
        float fraction = lerp(wetStart, wetEnd, alpha);
        float x = lerp(fromWorldX, toWorldX, fraction);
        float y = lerp(fromLayer, toLayer, fraction);
        Contact contact = sample(terrain, x, y,
                true, false, platformTick, availability);
        if (contact.kind != CustomMapRuntime.TerrainKind.WATER)
            contact = startContact;
        return new AirborneContact(x, y, fraction, contact);
    }

    private static AirborneContact earlierAirborne(
            AirborneContact first, AirborneContact second) {
        if (second == null) return first;
        if (first == null) return second;
        if (second.fraction < first.fraction - 0.0001f) return second;
        if (Math.abs(second.fraction - first.fraction) <= 0.0001f
                && second.contact != null && second.contact.grounded()
                && (first.contact == null || !first.contact.grounded()))
            return second;
        return first;
    }

    private static AirborneContact noAirborneHit(
            CustomMapDocument.ModeVariant terrain,
            float worldX, float actorLayer, boolean includeSecondary,
            long platformTick, SupportAvailability availability) {
        Contact endpoint = sample(terrain, worldX, actorLayer,
                true, includeSecondary, platformTick, availability);
        Contact none = new Contact(CustomMapRuntime.TerrainKind.VOID,
                Float.NaN, endpoint.inBounds, null);
        return new AirborneContact(worldX, actorLayer, 1f, none);
    }

    private static PlatformLanding secondaryLanding(
            List<PlatformSweep> sweeps,
            CustomMapDocument.ModeVariant terrain,
            float actorX0, float actorLayer0,
            float actorX1, float actorLayer1,
            float t0, float t1, float tolerance,
            SupportAvailability availability) {
        PlatformLanding best = null;
        for (PlatformSweep sweep : sweeps) {
            float center0 = lerp(sweep.previousCenterTileX,
                    sweep.currentCenterTileX, t0);
            float center1 = lerp(sweep.previousCenterTileX,
                    sweep.currentCenterTileX, t1);
            float support0 = sweep.platform.collisionSupportLayer(
                    lerp(sweep.previousSupportLayer,
                            sweep.currentSupportLayer, t0),
                    terrain.layerUnitsPerTile());
            float support1 = sweep.platform.collisionSupportLayer(
                    lerp(sweep.previousSupportLayer,
                            sweep.currentSupportLayer, t1),
                    terrain.layerUnitsPerTile());
            float relative0 = actorLayer0 - support0;
            float relative1 = actorLayer1 - support1;
            float relativeDelta = relative1 - relative0;

            if (relative0 > tolerance || relative1 < -tolerance
                    || relativeDelta < -0.0001f) continue;
            float alpha = relativeDelta > 0.0001f
                    ? clamp(-relative0 / relativeDelta, 0f, 1f) : 1f;
            float actorX = lerp(actorX0, actorX1, alpha);
            float center = lerp(center0, center1, alpha);
            float tileX = actorX / Math.max(1f, terrain.worldUnitsPerTile());
            if (tileX < sweep.platform.collisionLeftTileX(center)
                    || tileX >= sweep.platform.collisionRightTileX(center))
                continue;
            int localTileX = platformLocalTile(
                    sweep.platform, center, tileX);
            if (!availability.isAvailable(
                    terrain, sweep.platform, localTileX)) continue;

            float fraction = lerp(t0, t1, alpha);
            float support = lerp(support0, support1, alpha);
            if (best == null || fraction < best.fraction - 0.0001f
                    || (Math.abs(fraction - best.fraction) <= 0.0001f
                    && support < best.supportLayer))
                best = new PlatformLanding(
                        sweep, fraction, support, localTileX);
        }
        return best;
    }

    private static List<PlatformSweep> platformSweeps(
            CustomMapDocument.ModeVariant terrain, long platformTick) {
        if (terrain == null || terrain.secondaryPlatforms == null)
            return Collections.emptyList();
        List<PlatformSweep> out = new ArrayList<PlatformSweep>();
        for (CustomMapDocument.SecondaryPlatform platform
                : terrain.secondaryPlatforms) {
            if (platform == null || platform.widthTiles() <= 0) continue;
            if (platform.isPatrolling()) {
                long localTick = CustomMapRuntime.platformEvaluationTick(
                        terrain, platform, platformTick);
                MovingPlatformEngine.Pose current = MovingPlatformEngine.poseAtTick(
                        terrain, platform, localTick);
                MovingPlatformEngine.Pose previous = localTick <= 0L
                        ? current : MovingPlatformEngine.poseAtTick(
                        terrain, platform, localTick - 1L);
                out.add(new PlatformSweep(platform, current,
                        previous.centerTileX, current.centerTileX,
                        previous.supportLayer(terrain),
                        current.supportLayer(terrain)));
            } else {
                float center = platform.originCenterTileX();
                out.add(new PlatformSweep(platform, null,
                        center, center, platform.supportLayer,
                        platform.supportLayer));
            }
        }
        return out;
    }

    public static Sweep sweepMain(CustomMapDocument.ModeVariant terrain,
                                  float fromWorldX, float toWorldX,
                                  float maximumRiseLayer) {
        return sweepMain(terrain, fromWorldX, toWorldX, maximumRiseLayer,
                ALLOW_ALL);
    }

    public static Sweep sweepMain(CustomMapDocument.ModeVariant terrain,
                                  float fromWorldX, float toWorldX,
                                  float maximumRiseLayer,
                                  SupportAvailability availability) {
        availability = availability == null ? ALLOW_ALL : availability;
        if (terrain == null)
            return new Sweep(toWorldX, sample(null, toWorldX, 0f, false, false), true);
        float distance = toWorldX - fromWorldX;
        int steps = Math.max(1, (int) Math.ceil(Math.abs(distance)
                / Math.max(1f, terrain.worldUnitsPerTile() / 8f)));
        float lastX = fromWorldX;
        Contact last = sample(terrain, fromWorldX,
                terrain.surfaceLayerAt(fromWorldX), false, false, 0L,
                availability);
        for (int i = 1; i <= steps; i++) {
            float x = fromWorldX + distance * i / steps;
            Contact next = sample(terrain, x,
                    last == null ? 0f : last.supportLayer,
                    false, false, 0L, availability);
            if (next.kind != CustomMapRuntime.TerrainKind.MAIN)
                return new Sweep(lastX, next, true);
            if (last != null && last.kind == CustomMapRuntime.TerrainKind.MAIN
                    && Math.abs(next.supportLayer - last.supportLayer) > maximumRiseLayer)
                return new Sweep(lastX, next, true);
            lastX = x;
            last = next;
        }
        return new Sweep(toWorldX, last, false);
    }

    public static MainStep firstMainStep(CustomMapDocument.ModeVariant terrain,
                                         float fromWorldX, float toWorldX) {
        return firstMainStep(terrain, fromWorldX, toWorldX, ALLOW_ALL);
    }

    public static MainStep firstMainStep(CustomMapDocument.ModeVariant terrain,
                                         float fromWorldX, float toWorldX,
                                         SupportAvailability availability) {
        availability = availability == null ? ALLOW_ALL : availability;
        if (terrain == null || terrain.surface == null
                || fromWorldX == toWorldX
                || !terrain.containsWorldX(fromWorldX)) return null;
        int direction = toWorldX > fromWorldX ? 1 : -1;
        float boundedTo = Math.max(0f, Math.min(
                terrain.worldWidth() - 0.001f, toWorldX));
        int fromTile = (int) Math.floor(fromWorldX / terrain.worldUnitsPerTile());
        int toTile = (int) Math.floor(boundedTo / terrain.worldUnitsPerTile());
        if (fromTile == toTile) return null;

        int tile = fromTile;
        while (tile != toTile) {
            int next = tile + direction;
            if (next < 0 || next >= terrain.width) return null;
            if (isDryMain(terrain, tile, availability)
                    && isDryMain(terrain, next, availability)
                    && !terrain.isContinuousSurfaceBetween(tile, next)) {
                float fromLayer = terrain.walkLayerAtTile(tile);
                float toLayer = terrain.walkLayerAtTile(next);
                if (!Float.isNaN(fromLayer) && !Float.isNaN(toLayer)) {
                    float tolerance = Math.max(0.01f,
                            terrain.layerUnitsPerTile() * 0.015f);
                    if (Math.abs(toLayer - fromLayer) > tolerance) {
                        StepKind kind = toLayer < fromLayer
                                ? StepKind.UP : StepKind.DOWN;
                        float boundary = direction > 0
                                ? (tile + 1) * terrain.worldUnitsPerTile()
                                : tile * terrain.worldUnitsPerTile();
                        return new MainStep(kind, tile, next, direction,
                                boundary, fromLayer, toLayer);
                    }
                }
            }
            tile = next;
        }
        return null;
    }

    public static CustomMapDocument.NavigationLink link(
            CustomMapDocument.ModeVariant terrain, float worldX, int direction,
            CustomMapDocument.NavigationType type) {
        if (terrain == null || direction == 0 || type == null) return null;
        List<CustomMapDocument.NavigationLink> links = terrain.navigationLinks == null
                ? Collections.<CustomMapDocument.NavigationLink>emptyList()
                : terrain.navigationLinks;
        int tile = tileAt(terrain, worldX);
        for (CustomMapDocument.NavigationLink link : links) {
            if (link == null || link.type != type) continue;
            if (direction > 0 && link.fromX == tile && link.toX > tile) return link;
            if (direction < 0 && link.bidirectional
                    && link.toX == tile && link.fromX < tile) return link;
        }
        return null;
    }

    public static CustomMapDocument.NavigationLink containingLink(
            CustomMapDocument.ModeVariant terrain, float worldX,
            CustomMapDocument.NavigationType type) {
        if (terrain == null || type == null || !terrain.containsWorldX(worldX)) return null;
        int tile = tileAt(terrain, worldX);
        List<CustomMapDocument.NavigationLink> links = terrain.navigationLinks == null
                ? Collections.<CustomMapDocument.NavigationLink>emptyList()
                : terrain.navigationLinks;
        for (CustomMapDocument.NavigationLink link : links)
            if (link != null && link.type == type
                    && tile >= link.spanStartX && tile <= link.spanEndX) return link;
        return null;
    }

    public static float waterLayer(CustomMapDocument.ModeVariant terrain, int tile) {
        if (terrain == null || tile < 0 || tile >= terrain.width) return Float.NaN;
        for (int row = 0; row < terrain.height; row++)
            if (terrain.cell(tile, row) == CustomMapDocument.CELL_WATER)
                return -(terrain.height - row) * terrain.layerUnitsPerTile();
        return Float.NaN;
    }

    public static int tileAt(CustomMapDocument.ModeVariant terrain, float worldX) {
        if (terrain == null || terrain.width <= 0) return -1;
        return Math.max(0, Math.min(terrain.width - 1,
                (int) Math.floor(worldX / terrain.worldUnitsPerTile())));
    }

    private static boolean isDryMain(CustomMapDocument.ModeVariant terrain,
                                     int tile,
                                     SupportAvailability availability) {
        return terrain != null && tile >= 0 && tile < terrain.width
                && terrain.surface != null && terrain.surface[tile] >= 0
                && (terrain.water == null || !terrain.water[tile])
                && availability.isAvailable(terrain, null, tile);
    }

    private static PlatformSupport secondarySupport(
            CustomMapDocument.ModeVariant terrain, float worldX, float actorLayer,
            boolean falling, long platformTick,
            SupportAvailability availability) {
        if (terrain.secondaryPlatforms == null) return null;
        float tolerance = terrain.layerUnitsPerTile() * (falling ? 0.40f : 0.22f);
        PlatformSupport best = null;
        float tileX = worldX / Math.max(1f, terrain.worldUnitsPerTile());
        for (CustomMapDocument.SecondaryPlatform platform : terrain.secondaryPlatforms) {
            if (platform == null || platform.widthTiles() <= 0) continue;
            MovingPlatformEngine.Pose pose = platform.isPatrolling()
                    ? MovingPlatformEngine.poseAtTick(terrain, platform,
                    CustomMapRuntime.platformEvaluationTick(
                            terrain, platform, platformTick))
                    : null;
            float centerTileX = pose == null
                    ? platform.originCenterTileX() : pose.centerTileX;
            if (tileX < platform.collisionLeftTileX(centerTileX)
                    || tileX >= platform.collisionRightTileX(centerTileX)) continue;
            int localTileX = platformLocalTile(
                    platform, centerTileX, tileX);
            if (!availability.isAvailable(
                    terrain, platform, localTileX)) continue;
            float baseSupportLayer = pose == null
                    ? platform.supportLayer : pose.supportLayer(terrain);
            float supportLayer = platform.collisionSupportLayer(
                    baseSupportLayer, terrain.layerUnitsPerTile());

            if (actorLayer > supportLayer + tolerance) continue;
            if (best == null || supportLayer < best.supportLayer)
                best = new PlatformSupport(
                        platform, pose, supportLayer, localTileX);
        }
        return best;
    }

    private static int platformLocalTile(
            CustomMapDocument.SecondaryPlatform platform,
            float centerTileX, float worldTileX) {
        int width = platform == null ? 0 : platform.widthTiles();
        if (width <= 0) return -1;
        int local = (int) Math.floor(
                worldTileX - (centerTileX - width * .5f));
        return Math.max(0, Math.min(width - 1, local));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class PlatformSweep {
        final CustomMapDocument.SecondaryPlatform platform;
        final MovingPlatformEngine.Pose currentPose;
        final float previousCenterTileX;
        final float currentCenterTileX;
        final float previousSupportLayer;
        final float currentSupportLayer;

        PlatformSweep(CustomMapDocument.SecondaryPlatform platform,
                      MovingPlatformEngine.Pose currentPose,
                      float previousCenterTileX, float currentCenterTileX,
                      float previousSupportLayer, float currentSupportLayer) {
            this.platform = platform;
            this.currentPose = currentPose;
            this.previousCenterTileX = previousCenterTileX;
            this.currentCenterTileX = currentCenterTileX;
            this.previousSupportLayer = previousSupportLayer;
            this.currentSupportLayer = currentSupportLayer;
        }
    }

    private static final class PlatformLanding {
        final PlatformSweep sweep;
        final float fraction;
        final float supportLayer;
        final int localTileX;

        PlatformLanding(PlatformSweep sweep, float fraction,
                        float supportLayer, int localTileX) {
            this.sweep = sweep;
            this.fraction = fraction;
            this.supportLayer = supportLayer;
            this.localTileX = localTileX;
        }
    }

    private static final class PlatformSupport {
        final CustomMapDocument.SecondaryPlatform platform;
        final MovingPlatformEngine.Pose pose;
        final float supportLayer;
        final int localTileX;

        PlatformSupport(CustomMapDocument.SecondaryPlatform platform,
                        MovingPlatformEngine.Pose pose, float supportLayer,
                        int localTileX) {
            this.platform = platform;
            this.pose = pose;
            this.supportLayer = supportLayer;
            this.localTileX = localTileX;
        }
    }
}
