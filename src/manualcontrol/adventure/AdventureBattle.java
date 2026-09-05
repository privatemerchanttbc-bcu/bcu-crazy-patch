package manualcontrol.adventure;

import common.battle.StageBasis;
import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.util.stage.Stage;
import common.util.unit.EForm;
import common.util.unit.Form;
import common.util.unit.Level;
import manualcontrol.Logger;
import manualcontrol.custommap.CustomMapDocument;
import manualcontrol.custommap.CustomMapRuntime;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class AdventureBattle {

    private static final int SPAWN_FREEZE = 1_000_000;

    private static final int PLAYER_SPAWN_DELAY = 5;

    private static final int RESPAWN_DELAY = 45;

    private static final int MIN_ALIVE = 8;

    private static final int SPAWN_STAGGER = 8;

    final StageBasis sb;
    final WeakReference<Object> pageRef;
    final AdventureController controller = new AdventureController();
    final AdventureCamera camera = new AdventureCamera();
    final AdventureHud hud = new AdventureHud();
    final AdventureWaveSpawner spawner;
    final AdventureEnemyAI enemyAI = new AdventureEnemyAI();
    final AdventureDoor door = new AdventureDoor();
    final AdventureSpawnFx spawnFx = new AdventureSpawnFx();
    final AdventureConjure conjure = new AdventureConjure();
    final AdventureCoreEffects coreEffects = new AdventureCoreEffects(this);
    final AdventureAfterimage afterimages = new AdventureAfterimage(this);
    final AdventureTeleport teleport = new AdventureTeleport(this);
    final AdventureProjectiles projectiles = new AdventureProjectiles(this);
    final AdventureCoreVfx coreVfx = new AdventureCoreVfx(this);
    final AdventureAdvancedCores advancedCores = new AdventureAdvancedCores(this);
    final AdventureArchitect architect = new AdventureArchitect(this);
    private AdventureCoreOverlay coreOverlay;

    private long basePlayerMaxH;

    private long baseFormMaxH;

    private final List<EEnemy> enemies = new ArrayList<EEnemy>();

    private final java.util.Map<Object, Float> kbPre = new java.util.WeakHashMap<Object, Float>();
    private final String levelName;

    private EUnit player;
    private boolean playerSpawned;
    private int respawnCountdown = -1;
    private int spawnStagger;
    private int transitionDelay = -1;
    private int pendingLevel = -1;
    private boolean levelCleared;
    private boolean gameOver;
    private boolean transitioning;
    private boolean paused;
    private boolean leaving;
    private int customHazardIframes;
    private AdventurePauseOverlay pauseOverlay;

    AdventureBattle(StageBasis sb, Object page) {
        this.sb = sb;
        this.pageRef = new WeakReference<Object>(page);
        this.spawner = new AdventureWaveSpawner(sb);
        this.levelName = resolveLevelName(sb.st);
        CustomMapDocument.ModeVariant custom = CustomMapRuntime.activeVariant(CustomMapDocument.MapMode.ADVENTURE);
        if (custom != null) {
            CustomMapRuntime.resetMovingPlatforms();
            door.setWorldX(custom.worldX(custom.destination.x));
        }
        coreEffects.onLevelStart();
        teleport.onLevelStart();
        advancedCores.onLevelStart();
        architect.onLevelStart();
        coreVfx.onLevelStart();
    }

    public EUnit player() { return player; }

    public Object page() { return pageRef.get(); }

    String levelName() { return levelName; }

    int remainingEnemies() { return enemies.size() + spawner.remainingQueued(); }

    boolean isGameOver() { return gameOver; }

    boolean ownsEntity(Object entity) {
        if (entity == null) return false;
        if (entity == player) return true;

        return enemies.contains(entity);
    }

    java.util.List<EEnemy> liveEnemies() { return enemies; }

    private static final long BASE_PIN_HP = Long.MAX_VALUE / 4;

    void tick() {
        sb.respawnTime = SPAWN_FREEZE;
        sb.cannon = 0;
        fortifyBases();
        manualcontrol.crazy.collision.PhysicalCollision.ENABLED = true;
        if (leaving) return;
        if (paused) {
            if (pauseOverlay != null) { pauseOverlay.tick(); handlePauseAction(); }
            return;
        }
        if (!levelCleared && !gameOver && !transitioning)
            manualcontrol.custommap.CustomMapRuntime.tickMovingPlatforms(false);
        spawnFx.tick();
        afterimages.tick();
        teleport.tick();
        projectiles.tick();
        coreVfx.tick();
        if (customHazardIframes > 0) customHazardIframes--;
        conjure.tick();
        if (AdventureRuntime.cores().hasUnique("L2")) conjure.tick();

        if (!playerSpawned && sb.time >= PLAYER_SPAWN_DELAY) {
            spawnPlayer();
        }
        advancedCores.beforeTick(player);
        advancedCores.tick(player);
        architect.tick(player, controller);

        if (player != null && !isPlayerDead() && !levelCleared) {
            if (spawnStagger > 0) spawnStagger--;
            if (enemies.size() < MIN_ALIVE && spawnStagger == 0 && !spawner.queueEmpty()) {
                EEnemy spawned = spawner.pop(player.pos);
                if (spawned != null) {

                    if (AdventureRuntime.cores().hasUnique("L3") && rng.nextFloat() < 0.25f) {
                        spawned.dire = -1;
                        manualcontrol.ConvertedRegistry.mark(spawned);
                        addEntitySorted(sb, spawned);
                        spawnFx.spawnColored(spawned.pos, spawned.currentLayer,
                                195, 107, 255, 255, 200, 255);
                    } else {
                        enemies.add(spawned);
                        addEntitySorted(sb, spawned);
                        spawnFx.spawn(spawned.pos, spawned.currentLayer, true);
                    }
                    spawnStagger = SPAWN_STAGGER;
                }
            }
        }

        tickLegendChaos();

        if (player != null && !isPlayerDead()) {

            controller.tick(player, -625f, sb.st.len + 625f, teleport,
                    architect, advancedCores, spawnFx);
            tickCustomTerrain();
            if (AdventureController.canDrive(player)
                    && !controller.isAirborne()) driveAnim(player);
            coreEffects.tick(player);
            if (controller.consumeJustLanded()) {

                coreEffects.onPlayerLanded(
                        player, controller.consumePlatformDropTiles());
                reviveStompedZombies();
            }
            if (AdventureInput.consumeConjure()) conjure.tryConjure(sb, player);
            AdventureBridge.setFlipped(player, controller.isFacingRight());

            if (!AdventureController.isAttackActive(player)) {
                float[] pt = manualcontrol.crazy.collision.SpriteBounds.pivotAndTop(player);
                if (pt != null) {
                    AdventureBridge.setPlayerSpriteTop(pt[1]);
                    if (Float.isNaN(AdventureBridge.mirrorCenter(player))) {
                        AdventureBridge.setMirrorCenter(player, pt[0]);
                    }
                }

                if (AdventureBridge.shadowPart(player) == null) {
                    AdventureBridge.setShadowPart(player,
                            manualcontrol.crazy.collision.SpriteBounds.shadowPartIndex(player));
                }
            }
            controller.noteIceOccupancy(player);
        }
        for (int i = 0; i < enemies.size(); i++) {
            EEnemy e = enemies.get(i);
            enemyAI.tick(e, player, architect, spawnFx);
            enemyAI.noteIceOccupancy(e);
            if (AdventureController.canDrive(e)) driveAnim(e);

            if (Float.isNaN(AdventureBridge.mirrorCenter(e))
                    && !AdventureController.isAttackActive(e)) {
                float c = manualcontrol.crazy.collision.SpriteBounds.shadowCenterX(e);
                if (c == c) AdventureBridge.setMirrorCenter(e, c);
            }

            if (AdventureController.readKbTime(e) != 0) kbPre.put(e, e.pos);
            else kbPre.remove(e);
        }

        if (player != null) {
            if (AdventureController.readKbTime(player) != 0) kbPre.put(player, player.pos);
            else kbPre.remove(player);
        }
        noteUnmanagedIceOccupants();
        if (!levelCleared && !gameOver && !transitioning)
            CustomMapRuntime.tickLiquidHazards();
    }

    private void noteUnmanagedIceOccupants() {
        CustomMapDocument.ModeVariant terrain = CustomMapRuntime.activeVariant(
                CustomMapDocument.MapMode.ADVENTURE);
        if (terrain == null || sb.le == null) return;
        float tolerance = terrain.layerUnitsPerTile() * .30f;
        for (Entity entity : sb.le) {
            if (entity == null || entity == player || enemies.contains(entity)
                    || entity.isBase() || entity.dead || entity.health <= 0L)
                continue;
            CustomMapRuntime.TerrainSample support =
                    CustomMapRuntime.sampleTerrain(entity.pos,
                            entity.currentLayer, false);
            boolean grounded = support.hasSupport()
                    && Math.abs(entity.currentLayer - support.supportLayer)
                    <= tolerance;
            CustomMapRuntime.noteIceOccupant(entity, entity.pos,
                    entity.currentLayer, grounded, support.platformId);
        }
    }

    private void reviveStompedZombies() {
        if (player == null) return;
        manualcontrol.crazy.collision.SpriteBounds.WorldBox playerBox =
                manualcontrol.crazy.collision.SpriteBounds.of(player);
        for (int i = 0; i < enemies.size(); i++) {
            EEnemy enemy = enemies.get(i);
            if (enemy == null || !AdventureController.isZombieReviveActive(enemy)
                    || !AdventureController.hasCorpse(enemy)) {
                continue;
            }

            boolean stomped;
            manualcontrol.crazy.collision.SpriteBounds.WorldBox enemyBox =
                    manualcontrol.crazy.collision.SpriteBounds.of(enemy);
            if (playerBox != null && enemyBox != null) {
                stomped = playerBox.overlapsX(enemyBox);
            } else {
                stomped = Math.abs(player.pos - enemy.pos)
                        <= landingHalfWidth(player) + landingHalfWidth(enemy);
            }

            if (stomped && AdventureController.finishZombieRevive(enemy)) {
                spawnFx.spawn(enemy.pos, enemy.currentLayer, true);
                Logger.log("Adventure: landing revived zombie at " + Math.round(enemy.pos));
            }
        }
    }

    private static float landingHalfWidth(Object entity) {
        try {
            Object data = manualcontrol.reflect.BCUFields.get(entity, "data");
            Object width = manualcontrol.reflect.BCUFields.invoke(data, "getWidth");
            if (width instanceof Number) {
                return Math.max(60f, ((Number) width).floatValue() * 0.5f);
            }
        } catch (Throwable ignored) {}
        return 100f;
    }

    void driveAnim(Object entity) {
        int mult = animMult(entity);
        for (int i = 0; i < mult; i++) {
            AdventureController.tickAnim(entity);
        }
    }

    private int animMult(Object entity) {
        if (entity == player && AdventureController.isAttackActive(entity)) {
            return controller.atkStepsThisTick();
        }
        if (entity instanceof EEnemy && AdventureController.isAttackActive(entity)
                && AdventureRuntime.cores().hasUnique("P4")
                && AdventureEnemyAI.isDilationSkipTick((EEnemy) entity)) {
            return 0;
        }
        return 1;
    }

    private static final int TRANSITION_DELAY = 18;

    void afterTick() {
        if (transitioning) { advanceTransition(); return; }
        correctKnockback();
        advancedCores.afterTick(player);
        pruneDeadEnemies();
        handlePlayerDeath();
        checkLevelClear();
        checkDoorEntry();
    }

    private void correctKnockback() {
        if (player == null || kbPre.isEmpty()) return;
        for (int i = 0; i < enemies.size(); i++) {
            EEnemy e = enemies.get(i);
            Float pre = kbPre.get(e);
            if (pre == null) continue;
            float delta = e.pos - pre;
            if (delta == 0f) continue;
            float toPlayer = player.pos - pre;
            if (delta * toPlayer > 0f) {
                e.pos = pre - delta;
                e.lastPosition = e.pos;
            }
        }

        Float pre = kbPre.get(player);
        float bul = AdventureRuntime.cores().bulwarkPct();
        if (pre != null && bul > 0f) {
            float delta = player.pos - pre;
            if (delta != 0f) {
                player.pos = pre + delta * (1f - bul);
                player.lastPosition = player.pos;
                kbPre.put(player, player.pos);
            }
        }
    }

    AdventureDoor door() { return door; }

    boolean showDoor() { return levelCleared && !transitioning && !gameOver; }

    private void fortifyBases() {
        try {
            if (sb.ebase != null) {
                sb.ebase.maxH = BASE_PIN_HP;
                sb.ebase.health = BASE_PIN_HP;
            }
            if (sb.ubase != null) {
                sb.ubase.maxH = BASE_PIN_HP;
                sb.ubase.health = BASE_PIN_HP;
            }
        } catch (Throwable ignored) {}
    }

    private void checkDoorEntry() {
        if (!levelCleared || transitioning || gameOver || player == null || isPlayerDead()) return;
        if (door.canEnter(player.pos)) {
            transitioning = true;
            spawnFx.flash();
            transitionDelay = TRANSITION_DELAY;
            int next = AdventureRuntime.levelIndex() + 1;
            pendingLevel = AdventureRuntime.isCustomMapRun()
                    ? -2 : (next < AdventureLauncher.levels().size() ? next : -2);
            Logger.log("Adventure: door entered - advancing to " + pendingLevel);
        }
    }

    private void advanceTransition() {
        if (transitionDelay > 0) { transitionDelay--; return; }
        if (transitionDelay == 0) {
            transitionDelay = -1;
            if (pendingLevel == -2) {
                Logger.log("Adventure: all levels cleared - VICTORY");
                AdventureTransition.finish(page(), true);
                return;
            }
            if (pendingLevel >= 0) {
                openCoreSelection();
            }
            return;
        }

        if (coreOverlay != null) {
            coreOverlay.tick();
            if (coreOverlay.isDone()) {
                AdventureCore picked = coreOverlay.picked();
                coreOverlay = null;
                if (picked != null) {
                    AdventureRuntime.cores().add(picked);
                    AdventureRuntime.setLastPicked(picked);
                    applyHpCores();
                    if (picked.family == AdventureCoreCatalog.F_REFINE) {
                        hud.showMessage("REFINED: " + (int) picked.value
                                + " CORE(S) +1 TIER", 255, 210, 74);
                    }

                    float fx = player != null ? player.pos : door.worldX();
                    int fl = player != null ? player.currentLayer : 0;
                    spawnFx.confirmFx(picked.tier, fx, fl);
                    if (picked.tier == AdventureCore.Tier.LEGEND) camera.shake(10);
                    postPickDelay = 26;
                    Logger.log("Adventure: core picked - " + picked);
                } else {
                    AdventureTransition.toLevel(page(), pendingLevel);
                }
            }
            return;
        }
        if (postPickDelay > 0 && --postPickDelay == 0) {
            AdventureTransition.toLevel(page(), pendingLevel);
        }
    }

    private int postPickDelay;
    private final java.util.Random rng = new java.util.Random();
    private int quakeTimer;
    private int rouletteTimer;

    private void tickLegendChaos() {
        if (player == null || isPlayerDead() || levelCleared || transitioning) return;
        AdventureCoreState cores = AdventureRuntime.cores();

        if (cores.hasUnique("L4") && ++quakeTimer >= 300) {
            quakeTimer = 0;
            for (int i = 0; i < enemies.size(); i++) {
                EEnemy e = enemies.get(i);
                if (e == null || e.dead) continue;
                float nx = e.pos + (rng.nextFloat() * 2f - 1f) * 600f;
                e.pos = Math.max(250f, Math.min(sb.st.len - 250f, nx));
                e.lastPosition = e.pos;
                spawnFx.spawn(e.pos, e.currentLayer, true);
            }
            spawnFx.flash();
            camera.shake(8);
            AdventureSfx.play(AdventureSfx.CONFIRM_LEGEND);
            hud.showMessage("CHAOS QUAKE!", 195, 107, 255);
        }

        if (cores.hasUnique("L5") && ++rouletteTimer >= 150) {
            rouletteTimer = 0;
            spinRoulette(cores);
        }
    }

    private void spinRoulette(AdventureCoreState cores) {
        int roll = rng.nextInt(5);
        switch (roll) {
            case 0: {
                player.health = Math.min(player.maxH, player.health + Math.round(player.maxH * 0.10f));
                spawnFx.spawnColored(player.pos, player.currentLayer, 124, 255, 142, 220, 255, 230);
                hud.showMessage("ROULETTE: HEAL", 124, 255, 142);
                break;
            }
            case 1: {
                long dmg = AdventureCombat.currentPlayerDamage(player);
                for (int i = 0; i < enemies.size(); i++) {
                    EEnemy e = enemies.get(i);
                    if (e == null || e.dead || e.health <= 0L) continue;
                    if (AdventureController.isRevivingCorpse(e)) continue;
                    if (AdventureCombat.overlapsHorizontal(
                            e, player.pos - 400f, player.pos + 400f)) {
                        AdventureCombat.queueEffectDamage(e, dmg);
                    }
                }
                spawnFx.spawnColored(player.pos, player.currentLayer, 255, 90, 90, 255, 200, 150);
                AdventureSfx.play(AdventureSfx.CONFIRM_LEGEND);
                hud.showMessage("ROULETTE: NOVA", 255, 120, 90);
                break;
            }
            case 2: {
                player.health = Math.max(1L, player.health - Math.round(player.maxH * 0.05f));
                camera.shake(4);
                hud.showMessage("ROULETTE: PAIN", 255, 142, 124);
                break;
            }
            case 3: {
                cores.rouletteSurge(90);
                spawnFx.spawnColored(player.pos, player.currentLayer, 255, 210, 74, 255, 240, 180);
                hud.showMessage("ROULETTE: POWER x2", 255, 210, 74);
                break;
            }
            default: {
                EEnemy nearest = null;
                float best = Float.MAX_VALUE;
                for (int i = 0; i < enemies.size(); i++) {
                    EEnemy e = enemies.get(i);
                    if (e == null || e.dead) continue;
                    float d = Math.abs(e.pos - player.pos);
                    if (d < best) { best = d; nearest = e; }
                }
                if (nearest != null) {
                    float dir = Math.signum(nearest.pos - player.pos);
                    if (dir == 0f) dir = 1f;
                    nearest.pos = Math.max(250f, Math.min(sb.st.len - 250f, nearest.pos + dir * 500f));
                    nearest.lastPosition = nearest.pos;
                    spawnFx.spawn(nearest.pos, nearest.currentLayer, true);
                }
                hud.showMessage("ROULETTE: BANISH", 195, 107, 255);
                break;
            }
        }
    }

    private void openCoreSelection() {
        try {
            int stageCleared = AdventureRuntime.levelIndex() + 1;
            AdventureCore.Tier tier = AdventureCoreCatalog.tierForStage(stageCleared);
            AdventureCore[] offer = AdventureCoreCatalog.rollOffer(
                    tier, new java.util.Random(), AdventureRuntime.cores());
            if (offer == null || offer.length == 0) {
                AdventureTransition.toLevel(page(), pendingLevel);
                return;
            }

            float up = AdventureRuntime.cores().offerUpChance();
            if (up > 0f) {
                AdventureCore.Tier[] tiers = AdventureCore.Tier.values();
                int upgraded = 0;
                int lastUpgradable = -1;
                for (int i = 0; i < offer.length; i++) {
                    AdventureCore c = offer[i];
                    if (c.unique || c.family < 0) continue;
                    if (c.tier.ordinal() >= tiers.length - 1) continue;
                    AdventureCore.Tier nextTier = tiers[c.tier.ordinal() + 1];
                    if (!AdventureRuntime.cores().canOfferFamily(c.family, nextTier)) continue;
                    lastUpgradable = i;
                    if (rng.nextFloat() < up) {
                        offer[i] = AdventureCoreCatalog.of(c.family, nextTier);
                        upgraded++;
                    }
                }
                if (upgraded == 0 && lastUpgradable >= 0) {
                    AdventureCore c = offer[lastUpgradable];
                    offer[lastUpgradable] = AdventureCoreCatalog.of(c.family, tiers[c.tier.ordinal() + 1]);
                    upgraded = 1;
                }
                if (upgraded > 0) {
                    Logger.log("Adventure: Foresight upgraded " + upgraded + " card(s)");
                }
            }
            AdventureRuntime.cores().markSeen(offer);

            AdventureRuntime.saveCheckpoint();
            coreOverlay = new AdventureCoreOverlay(offer, stageCleared);
            AdventureInput.reset();
            AdventureSfx.play(AdventureSfx.OVERLAY_OPEN);
            Logger.log("Adventure: core offer (" + tier.label + "): "
                    + offer[0] + " | " + (offer.length > 1 ? offer[1] : "-")
                    + " | " + (offer.length > 2 ? offer[2] : "-"));
        } catch (Throwable t) {
            Logger.err("Adventure: core selection failed - skipping", t);
            coreOverlay = null;
            AdventureTransition.toLevel(page(), pendingLevel);
        }
    }

    boolean isChoosingCore() { return coreOverlay != null && !coreOverlay.isDone(); }

    AdventureCoreOverlay coreOverlay() { return coreOverlay; }

    boolean isPaused() { return paused; }

    AdventurePauseOverlay pauseOverlay() { return pauseOverlay; }

    void togglePause() {
        if (leaving || isChoosingCore() || transitioning || gameOver
                || player == null || isPlayerDead()) return;
        if (paused) {
            paused = false;
            pauseOverlay = null;
        } else {
            paused = true;
            pauseOverlay = new AdventurePauseOverlay(AdventureRuntime.isIronman());
            AdventureInput.reset();
            AdventureSfx.play(AdventureSfx.SELECT_MOVE);
        }
    }

    private void handlePauseAction() {
        int act = pauseOverlay == null ? AdventurePauseOverlay.NONE : pauseOverlay.consumeAction();
        switch (act) {
            case AdventurePauseOverlay.RESUME:
                paused = false;
                pauseOverlay = null;
                break;
            case AdventurePauseOverlay.RESTART:
                paused = false;
                pauseOverlay = null;
                leaving = true;
                AdventureTransition.toLevel(page(), AdventureRuntime.levelIndex());
                break;
            case AdventurePauseOverlay.SAVE_QUIT:
                paused = false;
                pauseOverlay = null;
                leaving = true;
                AdventureRuntime.saveCheckpoint();
                AdventureTransition.quitToMenu(page());
                break;
            default:
                break;
        }
    }

    private void spawnPlayer() {
        playerSpawned = true;
        Form form = AdventureRuntime.form();
        Level level = AdventureRuntime.level();
        if (form == null) {
            Logger.err("Adventure: no form selected - deactivating", null);
            AdventureBridge.deactivate("no-form");
            return;
        }
        try {
            player = createPlayer(form, level);
            Logger.log("Adventure: player spawned '" + form + "' hp=" + player.health
                    + " stage.len=" + sb.st.len);
        } catch (Throwable t) {
            Logger.err("Adventure: player spawn failed - deactivating", t);
            AdventureBridge.deactivate("player-spawn-failed");
        }
    }

    static final int HP_MULT = 3;

    static final int DAMAGE_MULT = 1;

    private EUnit createPlayer(Form form, Level level) {
        EForm ef = level == null ? new EForm(form, 30) : new EForm(form, level);
        EUnit u = ef.getEntity(sb, new int[]{0, 0}, false, false);
        AdventureCombat.resetPlayerDamage();
        CustomMapDocument.ModeVariant custom = CustomMapRuntime.activeVariant(CustomMapDocument.MapMode.ADVENTURE);
        float x = custom == null ? sb.st.len - 800f : custom.worldX(custom.spawn.x);
        u.added(-1, x);
        if (custom != null) {
            u.currentLayer = Math.round(custom.surfaceLayerAt(x));
            u.lastPosition = x;
        }
        try {
            baseFormMaxH = u.maxH;
            basePlayerMaxH = u.maxH * HP_MULT;
            u.maxH = coreMaxH();
            u.health = u.maxH;
        } catch (Throwable ignored) {}
        addEntitySorted(sb, u);
        controller.onPlayerSpawned(u);
        spawnFx.spawn(x, u.currentLayer, false);
        return u;
    }

    private long coreMaxH() {
        AdventureCoreState cores = AdventureRuntime.cores();
        return Math.round(basePlayerMaxH * cores.hpMult()
                + baseFormMaxH * cores.hpFlatPct());
    }

    private void applyHpCores() {
        if (player == null || basePlayerMaxH <= 0) return;
        try {
            long newMax = coreMaxH();
            long delta = newMax - player.maxH;
            if (delta == 0) return;
            player.maxH = newMax;
            player.health = Math.min(newMax, player.health + Math.max(0, delta));
        } catch (Throwable ignored) {}
    }

    private boolean isPlayerDead() {
        if (player == null) return false;
        return player.dead || AdventureController.readHealth(player) <= 0L;
    }

    private void handlePlayerDeath() {
        if (gameOver) return;

        if (respawnCountdown > 0) {
            respawnCountdown--;
            if (respawnCountdown == 0) {
                respawnCountdown = -1;
                respawnPlayer();
            }
            return;
        }

        if (player != null && isPlayerDead() && respawnCountdown < 0) {
            AdventureInput.clearQueuedActions();
            int left = AdventureRuntime.loseLife();
            Logger.log("Adventure: player died - lives left=" + left);
            if (left <= 0) {
                gameOver = true;
                transitioning = true;
                Logger.log("Adventure: GAME OVER - returning to menu");
                AdventureTransition.finish(page(), false);
            } else {
                respawnCountdown = RESPAWN_DELAY;
            }
        }
    }

    private void respawnPlayer() {
        Form form = AdventureRuntime.form();
        Level level = AdventureRuntime.level();
        if (form == null) return;
        try {
            if (player != null) sb.le.remove(player);
            player = createPlayer(form, level);
            Logger.log("Adventure: player respawned");
        } catch (Throwable t) {
            Logger.err("Adventure: respawn failed", t);
            AdventureBridge.deactivate("respawn-failed");
        }
    }

    private void pruneDeadEnemies() {
        for (int i = enemies.size() - 1; i >= 0; i--) {
            EEnemy e = enemies.get(i);
            if (e == null || e.dead || !sb.le.contains(e)) {
                enemies.remove(i);
                if (e != null) coreEffects.onEnemyKilled(e);
            }
        }
    }

    private void checkLevelClear() {
        if (levelCleared || gameOver) return;
        if (spawner.queueEmpty() && enemies.isEmpty() && playerSpawned
                && (spawner.totalPlanned() > 0 || AdventureRuntime.isCustomMapRun())) {
            levelCleared = true;
            Logger.log("Adventure: level cleared - " + levelName);

        }
    }

    boolean isLevelCleared() { return levelCleared; }

    boolean isCustomHazardInvulnerable() { return customHazardIframes > 0; }

    private void tickCustomTerrain() {
        if (player == null || CustomMapRuntime.activeVariant(CustomMapDocument.MapMode.ADVENTURE) == null) return;
        CustomMapRuntime.noteCheckpoint(player.pos);
        boolean voidFall = CustomMapRuntime.isOutOfBounds(player.pos)
                && CustomMapRuntime.belowVoidKillPlane(player.currentLayer);
        boolean waterHazard = !CustomMapRuntime.isLavaLiquidAt(
                player.pos, player.currentLayer)
                && CustomMapRuntime.isWaterHazard(player.pos, player.currentLayer);
        if ((!waterHazard && !voidFall)
                || customHazardIframes > 0) return;
        long loss = Math.max(1L, Math.round(player.maxH * 0.20f));
        player.health = Math.max(1L, player.health - loss);
        player.pos = CustomMapRuntime.respawnWorldX(player.pos);
        player.lastPosition = player.pos;
        player.currentLayer = Math.round(CustomMapRuntime.surfaceLayerAt(player.pos, 0));
        controller.onPlayerSpawned(player);
        customHazardIframes = 45;
        spawnFx.spawnColored(player.pos, player.currentLayer, 90, 185, 255, 220, 245, 255);
        hud.showMessage("WATER -20% HP  |  CHECKPOINT", 100, 205, 255);
    }

    private static String resolveLevelName(Stage st) {
        try {
            if (st == null) return "Adventure";
            String name = st.names != null ? st.names.toString() : null;
            if (name == null || name.trim().isEmpty()) name = st.name;
            if (name == null || name.trim().isEmpty()) name = String.valueOf(st);
            return name;
        } catch (Throwable ignored) {
            return "Adventure";
        }
    }

    static void addEntitySorted(StageBasis sb, Entity e) {
        sb.le.add(e);
        Collections.sort(sb.le, new Comparator<Entity>() {
            @Override
            public int compare(Entity a, Entity b) {
                return Integer.compare(a.currentLayer, b.currentLayer);
            }
        });
    }
}
