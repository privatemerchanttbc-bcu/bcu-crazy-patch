package manualcontrol.adventure;

import common.battle.attack.AttackAb;
import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import manualcontrol.crazy.collision.SpriteBounds;
import manualcontrol.crazy.collision.SpriteScale;
import manualcontrol.reflect.BCUFields;

final class AdventureCoreEffects {

    private final AdventureBattle battle;
    private final java.util.Random rng = new java.util.Random();
    private float healAcc;
    private long guardHealQueue;

    private Object lastGhostAttack;

    private Object lastProcAttack;

    AdventureCoreEffects(AdventureBattle battle) {
        this.battle = battle;
    }

    void onLevelStart() {
        healAcc = 0f;
        guardHealQueue = 0L;
        AdventureRuntime.cores().onStageStart();
    }

    void tick(EUnit player) {
        AdventureCoreState cores = AdventureRuntime.cores();
        cores.tickTransients();
        if (player == null || player.dead || player.health <= 0L) return;

        cores.setHealthy(player.maxH > 0 && player.health * 10L >= player.maxH * 7L);

        float regen = cores.regenPctPerSec();
        if (regen > 0f && player.health < player.maxH) {
            healAcc += player.maxH * regen / 100f / 30f;
            long whole = (long) healAcc;
            if (whole > 0) {
                healAcc -= whole;
                player.health = Math.min(player.maxH, player.health + whole);
            }
        }

        if (guardHealQueue > 0) {
            player.health = Math.min(player.maxH, player.health + guardHealQueue);
            guardHealQueue = 0L;
        }

        if (player.health * 5L < player.maxH && cores.tryPanicBlast()) {
            for (EEnemy e : battle.liveEnemies()) {
                if (e == null || e.dead) continue;
                float dir = Math.signum(e.pos - player.pos);
                if (dir == 0f) dir = 1f;
                e.pos += dir * 400f;
                e.lastPosition = e.pos;
            }
            iFrames(player, 45);
            battle.spawnFx.spawn(player.pos, player.currentLayer, false);
            AdventureSfx.play(AdventureSfx.CONFIRM_LEGEND);
            battle.hud.showMessage("PANIC BLAST!", 255, 210, 74);
        }
    }

    void onPlayerLanded(EUnit player) {
        onPlayerLanded(player, 0f);
    }

    void onPlayerLanded(EUnit player, float platformDropTiles) {
        if (player == null) return;
        AdventureCoreState cores = AdventureRuntime.cores();
        boolean bouncy = cores.hasUnique("S2");
        long dmg = bouncy
                ? Math.max(1L, AdventureCombat.currentPlayerDamage(player)) : 0L;
        long impact = manualcontrol.custommap.PlatformFallRules.impactDamage(
                platformDropTiles, player.maxH);
        if (!bouncy && impact <= 0L) return;

        float landingX = player.pos;
        float shadowWidth = AdventureLandingVfxRenderer.DEFAULT_SHADOW_WIDTH;
        SpriteBounds.ShadowMetrics shadow = SpriteBounds.shadowMetrics(player);
        if (shadow != null) {
            float spriteScale = SpriteScale.get(player);
            landingX += shadow.centerX * spriteScale / SpriteBounds.RAT;
            shadowWidth = shadow.width * spriteScale;
        }
        float landingRadius =
                AdventureLandingVfxRenderer.worldRadiusForShadowWidth(shadowWidth);

        int hits = 0;
        for (EEnemy e : battle.liveEnemies()) {
            if (e == null || e.dead || e.health <= 0L) continue;
            if (AdventureController.isRevivingCorpse(e)) continue;
            long resolved = 0L;
            if (bouncy && insideLandingShockwave(landingX, e, landingRadius))
                resolved = dmg;
            if (impact > 0L
                    && manualcontrol.crazy.collision.PhysicalCollision
                    .strictSpriteContact(player, e))
                resolved = Math.max(resolved, impact);
            if (resolved > 0L && AdventureCombat.queueEffectDamage(e, resolved)) {
                hits++;
            }
        }
        battle.spawnFx.spawnLanding(landingX, player.currentLayer,
                AdventureRuntime.landingVfx(), shadowWidth);
        battle.camera.shake(4);
        if (hits > 0) {
            manualcontrol.Logger.log("Adventure: merged landing hit " + hits
                    + " enemy(s), max damage=" + Math.max(dmg, impact));
        }
    }

    private static boolean insideLandingShockwave(float centerX, EEnemy enemy, float radius) {
        try {
            manualcontrol.crazy.collision.SpriteBounds.WorldBox box =
                    manualcontrol.crazy.collision.SpriteBounds.of(enemy);
            if (box != null) {
                return box.x1 >= centerX - radius && box.x0 <= centerX + radius;
            }
        } catch (Throwable ignored) {}

        float half = 100f;
        try {
            Object data = BCUFields.get(enemy, "data");
            Object width = BCUFields.invoke(data, "getWidth");
            if (width instanceof Number) {
                half = Math.max(60f, ((Number) width).floatValue() * 0.5f);
            }
        } catch (Throwable ignored) {}
        return enemy.pos + half >= centerX - radius
                && enemy.pos - half <= centerX + radius;
    }

    void onEnemyKilled(EEnemy e) {
        AdventureCoreState cores = AdventureRuntime.cores();
        cores.onEnemyKilled();

        EUnit p = battle.player();
        if (p != null && !p.dead && p.health > 0L && cores.hasUnique("G1") && rng.nextFloat() < 0.20f) {
            p.health = Math.min(p.maxH, p.health + Math.round(p.maxH * 0.05f));
            battle.spawnFx.spawnColored(p.pos, p.currentLayer, 255, 210, 74, 124, 255, 142);
        }

        if (e != null && cores.hasUnique("P3")) {
            long dmg = Math.max(1, AdventureCombat.currentPlayerDamage(battle.player()) / 2);
            try {
                for (EEnemy other : battle.liveEnemies()) {
                    if (other == null || other == e || other.dead || other.health <= 0L) continue;
                    if (AdventureController.isRevivingCorpse(other)) continue;
                    if (AdventureCombat.overlapsHorizontal(
                            other, e.pos - 400f, e.pos + 400f)) {
                        AdventureCombat.queueEffectDamage(other, dmg);
                    }
                }
                battle.spawnFx.spawn(e.pos, e.currentLayer, true);
            } catch (Throwable ignored) {}
        }
    }

    void onEntityDamaged(Object victim, Object attackObj, long healthBefore, long pendingBefore) {
        if (!(attackObj instanceof AttackAb)) return;
        AttackAb attack = (AttackAb) attackObj;
        Entity attacker = attack.attacker;
        EUnit player = battle.player();
        if (player == null) return;
        AdventureCoreState cores = AdventureRuntime.cores();
        long pendingAfter = pendingBefore;
        try { pendingAfter = Math.max(0L, BCUFields.getLong(victim, "damage")); }
        catch (Throwable ignored) {}
        long actualDamage = Math.max(0L, pendingAfter - Math.max(0L, pendingBefore));
        if (actualDamage <= 0L) return;

        if (attacker == player && victim instanceof EEnemy) {
            onPlayerHitEnemy(cores, player, (EEnemy) victim, attack, actualDamage);
        } else if (victim == player && attacker != null && attacker != player) {
            long immediate = battle.advancedCores.deferIncomingDamage(
                    player, actualDamage, pendingBefore, pendingAfter);
            onPlayerWasHit(cores, player, attacker, attack, immediate);
        }
    }

    private void onPlayerHitEnemy(AdventureCoreState cores, EUnit player, EEnemy e,
                                  AttackAb attack, long actualDamage) {
        try {
            cores.onPlayerHitLanded();

            if (cores.hasUnique("P8") && attack != lastGhostAttack) {
                lastGhostAttack = attack;
                battle.afterimages.onPlayerHitLanded();
            }

            if (attack != lastProcAttack
                    && (cores.waveChance() > 0f || cores.surgeChance() > 0f)) {
                lastProcAttack = attack;
                battle.projectiles.tryProc(player, e, Math.max(1L, attack.atk));
            }

            long atk = actualDamage;
            if (AdventureCombat.isDirectAttack(attack)) {
                battle.advancedCores.onDirectPlayerHit(e, attack, atk);
            }

            if (cores.hasUnique("L7") && rng.nextFloat() < 0.10f) {
                if (e.mark != 0) {
                    AdventureCombat.queueEffectDamage(e, Math.round(e.maxH * 0.10));
                } else {
                    AdventureCombat.queueEffectDamage(e, Math.max(1L, e.health));
                    battle.spawnFx.spawn(e.pos, e.currentLayer, true);
                    return;
                }
            }

            float bonus = 0f;
            if (cores.execBonus() > 0f && e.maxH > 0 && e.health * 4L < e.maxH) {
                bonus += cores.execBonus();
            }
            if (cores.giantBonus() > 0f && e.mark != 0) {
                bonus += cores.giantBonus();
            }
            float momentum = cores.momentumMult() - 1f;
            if (momentum > 0f) bonus += momentum;
            if (bonus > 0f) {
                AdventureCombat.queueEffectDamage(e, Math.round(atk * bonus));
            }

            if (cores.lifestealPct() > 0f && player.health > 0L) {
                long heal = Math.round(atk * cores.lifestealPct());
                if (heal > 0) player.health = Math.min(player.maxH, player.health + heal);
            }

            if (cores.hasUnique("P5")) {
                float toPlayer = player.pos - e.pos;
                float dist = Math.abs(toPlayer);
                if (dist > 80f) {
                    float pull = Math.min(120f, dist - 60f);
                    e.pos += Math.signum(toPlayer) * pull;
                    e.lastPosition = e.pos;
                }
            } else if (cores.knuckleDist() > 0f) {
                float dir = Math.signum(e.pos - player.pos);
                if (dir == 0f) dir = 1f;
                e.pos += dir * cores.knuckleDist();
                e.lastPosition = e.pos;
            }

            if (cores.warcryTicks() > 0) {
                Object statusArr = BCUFields.get(e, "status");
                int[] slow = ((int[][]) statusArr)[23];
                slow[0] = Math.max(slow[0], cores.warcryTicks());
                slow[1] = -50;
            }
        } catch (Throwable ignored) {}
    }

    private void onPlayerWasHit(AdventureCoreState cores, EUnit player, Entity attacker,
                                AttackAb attack, long actualDamage) {
        try {
            long atk = actualDamage;

            long pending = 0L;
            try { pending = BCUFields.getLong(player, "damage"); } catch (Throwable ignored) {}
            if (pending >= player.health && cores.trySecondWind()) {
                battle.advancedCores.clearDebt();
                try { BCUFields.field(player.getClass(), "damage").setLong(player, 0L); } catch (Throwable ignored) {}
                player.health = Math.max(1L, Math.round(player.maxH * cores.secondWindPct() / 100f));
                iFrames(player, 45);
                battle.spawnFx.spawn(player.pos, player.currentLayer, false);
                AdventureSfx.play(AdventureSfx.UNIQUE_STING);
                manualcontrol.Logger.log("Adventure: SECOND WIND - revived at "
                        + player.health + "/" + player.maxH);
                return;
            }

            if (pending >= player.health && cores.tryRewind()) {
                battle.advancedCores.clearDebt();
                try { BCUFields.field(player.getClass(), "damage").setLong(player, 0L); } catch (Throwable ignored) {}
                player.health = Math.max(1L, Math.round(player.maxH * 0.5f));
                iFrames(player, 45);
                battle.spawnFx.spawnColored(player.pos, player.currentLayer, 160, 200, 255, 235, 250, 255);
                AdventureSfx.play(AdventureSfx.UNIQUE_STING);
                battle.hud.showMessage("REWIND!", 160, 200, 255);
                manualcontrol.Logger.log("Adventure: REWIND - cheated death");
                return;
            }

            if (cores.hasUnique("G2") && rng.nextFloat() < 0.20f) {
                for (EEnemy e : battle.liveEnemies()) {
                    if (e == null || e.dead) continue;
                    if (Math.abs(e.pos - player.pos) > 500f) continue;
                    try {
                        int[] slow = ((int[][]) BCUFields.get(e, "status"))[23];
                        slow[0] = Math.max(slow[0], 90);
                        slow[1] = -80;
                    } catch (Throwable ignored) {}
                }
                battle.spawnFx.spawnColored(player.pos, player.currentLayer, 150, 220, 255, 235, 250, 255);
            }

            if (cores.guardPct() > 0f) {
                guardHealQueue += Math.round(atk * cores.guardPct());
            }

            if (cores.thornsPct() > 0f && attacker instanceof EEnemy && attacker.health > 0L) {
                AdventureCombat.queueEffectDamage(
                        attacker, Math.round(atk * cores.thornsPct()));
            }

            if (cores.aegisTicks() > 0) {
                iFrames(player, cores.aegisTicks());
            }
        } catch (Throwable ignored) {}
    }

    private static void iFrames(EUnit player, int ticks) {
        try {
            Object statusArr = BCUFields.get(player, "status");
            int[] s44 = ((int[][]) statusArr)[44];
            if (s44[0] < ticks) s44[0] = ticks;
        } catch (Throwable ignored) {}
    }
}
