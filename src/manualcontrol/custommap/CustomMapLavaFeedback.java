package manualcontrol.custommap;

import common.CommonStatic;

final class CustomMapLavaFeedback {

    static final int DAMAGE_SOUND_ID = 112;

    interface Output {
        void emitVfx(float worldX, float layer, int direction);
        void playSound(int soundId);
    }

    private static final Output PRODUCTION = new Output() {
        @Override public void emitVfx(float worldX, float layer, int direction) {
            CustomMapBattleRuntime.emitLavaDamageVfx(worldX, layer, direction);
        }

        @Override public void playSound(int soundId) {
            CommonStatic.setSE(soundId);
        }
    };

    private static volatile Output output = PRODUCTION;

    private CustomMapLavaFeedback() {}

    static void emit(float worldX, float layer, int direction) {
        Output target = output;
        try {
            target.emitVfx(worldX, layer, direction);
        } catch (Throwable ignored) {}
        try {
            target.playSound(DAMAGE_SOUND_ID);
        } catch (Throwable ignored) {}
    }

    static void setOutputForTesting(Output replacement) {
        output = replacement == null ? PRODUCTION : replacement;
    }
}
