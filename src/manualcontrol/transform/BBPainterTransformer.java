package manualcontrol.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class BBPainterTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "page/battle/BattleBox$BBPainter";
    private static final String HOOKS_CLASS = "manualcontrol/hooks/BBPainterHooks";
    private static final String POINT_DESC = "Ljava/awt/Point;";
    private static final String BATTLE_POINT_DESC = "Lcommon/system/P;";
    private static final String FAKEGRA_DESC = "Lcommon/system/fake/FakeGraphics;";
    private static final String CANNON_DESC = "Lcommon/battle/entity/Cannon;";
    private static final String CUSTOM_MAP_CLASS =
            "manualcontrol/custommap/CustomMapBattleRuntime";

    public static int transformCallCount = 0;
    public static int lastLogAt = 0;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        transformCallCount++;

        if (transformCallCount - lastLogAt >= 200 || transformCallCount <= 3) {
            manualcontrol.Logger.log("transform #" + transformCallCount + ": " + className);
            lastLogAt = transformCallCount;
        }

        if (className != null && (className.startsWith("page/") || className.startsWith("common/battle/")
                || className.startsWith("jogl/") || className.startsWith("main/"))) {
            manualcontrol.Logger.log("BCU CLASS: " + className + " (loader=" + (loader == null ? "boot" : loader.getClass().getSimpleName()) + ")");
        }
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new BBPainterPatcher(cw);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
            System.out.println("[ManualControl] *** PATCHED " + TARGET_CLASS + " ***");
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[ManualControl] Failed to patch " + TARGET_CLASS + ": " + t);
            t.printStackTrace();
            return null;
        }
    }

    static class BBPainterPatcher extends ClassVisitor {
        BBPainterPatcher(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            if (name.equals("press") && descriptor.equals("(" + POINT_DESC + ")V")) {
                return new InsertReturnHook(mv, access, name, descriptor, "onPress",
                        "(Ljava/lang/Object;" + POINT_DESC + ")Z", true);
            }
            if (name.equals("drag") && descriptor.equals("(" + POINT_DESC + ")V")) {
                return new InsertReturnHook(mv, access, name, descriptor, "onDrag",
                        "(Ljava/lang/Object;" + POINT_DESC + ")Z", true);
            }
            if (name.equals("release") && descriptor.equals("()V")) {
                return new InsertReturnHook(mv, access, name, descriptor, "onRelease",
                        "(Ljava/lang/Object;)Z", false);
            }
            if (name.equals("wheeled")
                    && descriptor.equals("(" + POINT_DESC + "I)V")) {
                return new WheelHook(mv, access, name, descriptor);
            }
            if (name.equals("draw") && descriptor.equals("(" + FAKEGRA_DESC + ")V")) {
                return new DrawPassHook(mv, access, name, descriptor, "onDraw",
                        "(Ljava/lang/Object;" + FAKEGRA_DESC + ")V");
            }

            if (name.equals("drawLineup")
                    && descriptor.equals("(" + FAKEGRA_DESC + "IIFFZI)V")) {
                return new DrawLineupOverlayHook(mv, access, name, descriptor);
            }
            if (name.equals("drawLineupWithTwoRows")
                    && descriptor.equals("(" + FAKEGRA_DESC + "IIFFF)V")) {
                return new DrawTwoRowLineupOverlayHook(mv, access, name, descriptor);
            }
            if (name.equals("drawBtm") && descriptor.equals("(" + FAKEGRA_DESC + ")V")) {
                return new SkipMethodHook(mv, access, name, descriptor, "skipBottomBar");
            }
            if (name.equals("drawTop") && descriptor.equals("(" + FAKEGRA_DESC + ")V")) {
                return new SkipMethodHook(mv, access, name, descriptor, "skipTopBar");
            }
            if (name.equals("drawCastle") && descriptor.equals("(" + FAKEGRA_DESC + ")V")) {
                return new DrawCastleVisitor(mv, access, name, descriptor);
            }

            if (name.equals("drawCastleHealthIndicator") && descriptor.equals("(" + FAKEGRA_DESC + ")V")) {
                return new BaseHealthProjectionVisitor(mv, access, name, descriptor);
            }
            if (name.equals("drawEntity") && descriptor.equals("(" + FAKEGRA_DESC + ")V")) {
                return new DrawEntityVisitor(mv);
            }
            if (name.equals("drawEff")
                    && descriptor.equals("(" + FAKEGRA_DESC
                    + "Lcommon/battle/attack/ContAb;"
                    + "Lcommon/system/fake/FakeTransform;F)V")) {
                return new DrawGroundEffectVisitor(mv);
            }
            return mv;
        }
    }

    static class CastleSkipHook extends AdviceAdapter {
        CastleSkipHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label cont = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "skipCastle", "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, cont);
            visitInsn(RETURN);
            visitLabel(cont);
        }
    }

    static class SkipMethodHook extends AdviceAdapter {
        private final String hookName;
        SkipMethodHook(MethodVisitor mv, int access, String name, String descriptor, String hookName) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.hookName = hookName;
        }
        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label cont = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, hookName, "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, cont);
            visitInsn(RETURN);
            visitLabel(cont);
        }
    }

    static class DrawCastleVisitor extends AdviceAdapter {
        private boolean enemyProjectionInserted;
        private boolean playerProjectionInserted;

        DrawCastleVisitor(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {

            org.objectweb.asm.Label cont = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "skipCastle", "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, cont);
            visitInsn(RETURN);
            visitLabel(cont);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, var);

            if (!enemyProjectionInserted && opcode == ASTORE && var == 2) {
                enemyProjectionInserted = true;
                visitVarInsn(ALOAD, 0);
                visitVarInsn(ALOAD, 1);
                visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "beginEnemyBaseDraw",
                        "(Ljava/lang/Object;" + FAKEGRA_DESC + ")V", false);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (!playerProjectionInserted
                    && opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("common/system/fake/FakeGraphics")
                    && name.equals("setTransform")
                    && descriptor.equals("(Lcommon/system/fake/FakeTransform;)V")) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                playerProjectionInserted = true;
                visitVarInsn(ALOAD, 0);
                visitVarInsn(ALOAD, 1);
                visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "beginPlayerBaseDraw",
                        "(Ljava/lang/Object;" + FAKEGRA_DESC + ")V", false);
                return;
            }
            if (opcode == Opcodes.INVOKESTATIC
                    && owner.equals(TARGET_CLASS)
                    && name.equals("drawNyCast")
                    && descriptor.equals("(" + FAKEGRA_DESC + "IIF[I)V")) {
                visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "manualcontrol/crazy/base/ExtraBasesFeature",
                        "drawNyCast",
                        "(" + FAKEGRA_DESC + "IIF[ILjava/lang/Object;)V",
                        false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        protected void onMethodExit(int opcode) {
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "endBaseDraw",
                    "(Ljava/lang/Object;" + FAKEGRA_DESC + ")V", false);
        }
    }

    static class DrawEntityVisitor extends MethodVisitor {
        private boolean nativeCannonHookInserted;

        DrawEntityVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }
        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && owner.equals("common/battle/entity/Entity$AnimManager")
                    && name.equals("draw")
                    && descriptor.equals("(Lcommon/system/fake/FakeGraphics;Lcommon/system/P;F)V")) {

                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "manualcontrol/hooks/DrawHook",
                        "drawAnim",
                        "(Ljava/lang/Object;Lcommon/system/fake/FakeGraphics;Lcommon/system/P;F)V",
                        false);
                return;
            }
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && owner.equals("common/battle/entity/Entity$AnimManager")
                    && name.equals("drawEff")
                    && descriptor.equals("(Lcommon/system/fake/FakeGraphics;Lcommon/system/P;F)V")) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "manualcontrol/hooks/DrawHook",
                        "drawEff",
                        "(Ljava/lang/Object;Lcommon/system/fake/FakeGraphics;Lcommon/system/P;F)V",
                        false);
                return;
            }
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && owner.equals("common/battle/entity/Cannon")
                    && name.equals("drawBase")
                    && descriptor.equals("(" + FAKEGRA_DESC + BATTLE_POINT_DESC + "F)V")) {
                visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, CUSTOM_MAP_CLASS,
                        "drawCannonBase",
                        "(" + CANNON_DESC + FAKEGRA_DESC + BATTLE_POINT_DESC
                                + "FLjava/lang/Object;)V", false);
                return;
            }
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && owner.equals("common/battle/entity/Cannon")
                    && name.equals("drawAtk")
                    && descriptor.equals("(" + FAKEGRA_DESC + BATTLE_POINT_DESC + "F)V")) {
                visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, CUSTOM_MAP_CLASS,
                        "drawCannonAttack",
                        "(" + CANNON_DESC + FAKEGRA_DESC + BATTLE_POINT_DESC
                                + "FLjava/lang/Object;)V", false);
                if (!nativeCannonHookInserted) {
                    nativeCannonHookInserted = true;
                    visitVarInsn(Opcodes.ALOAD, 0);
                    visitVarInsn(Opcodes.ALOAD, 1);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_CLASS,
                            "onDrawNativeCannonAttacks",
                            "(Ljava/lang/Object;" + FAKEGRA_DESC + ")V", false);
                }
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    static class DrawGroundEffectVisitor extends MethodVisitor {
        private boolean projectionInserted;

        DrawGroundEffectVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (!projectionInserted
                    && opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("common/system/fake/FakeGraphics")
                    && name.equals("setTransform")
                    && descriptor.equals("(Lcommon/system/fake/FakeTransform;)V")) {
                projectionInserted = true;
                visitVarInsn(Opcodes.ALOAD, 0);
                visitVarInsn(Opcodes.ALOAD, 1);
                visitVarInsn(Opcodes.ALOAD, 2);
                visitMethodInsn(Opcodes.INVOKESTATIC,
                        "manualcontrol/custommap/CustomMapBattleRuntime",
                        "translateGroundEffect",
                        "(Ljava/lang/Object;" + FAKEGRA_DESC
                                + "Ljava/lang/Object;)V",
                        false);
            }
        }
    }

    static class BaseHealthProjectionVisitor extends AdviceAdapter {
        BaseHealthProjectionVisitor(MethodVisitor mv, int access,
                                    String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label visible = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "skipCastle",
                    "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, visible);
            visitInsn(RETURN);
            visitLabel(visible);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC
                    && owner.equals("common/util/Res")
                    && name.equals("getBase")
                    && descriptor.equals("(Lcommon/battle/entity/AbEntity;"
                            + "Lcommon/system/SymCoord;Z)Lcommon/system/P;")) {
                visitVarInsn(ALOAD, 0);
                super.visitMethodInsn(INVOKESTATIC,
                        "manualcontrol/custommap/CustomMapBattleRuntime",
                        "drawProjectedBaseIndicator",
                        "(Lcommon/battle/entity/AbEntity;"
                                + "Lcommon/system/SymCoord;ZLjava/lang/Object;)"
                                + "Lcommon/system/P;",
                        false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    static class DrawPassHook extends InsertEndHook {
        DrawPassHook(MethodVisitor mv, int access, String name, String descriptor,
                     String hookName, String hookDesc) {
            super(mv, access, name, descriptor, hookName, hookDesc);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            boolean entityPass = opcode == INVOKESPECIAL
                    && owner.equals(TARGET_CLASS)
                    && name.equals("drawEntity")
                    && descriptor.equals("(" + FAKEGRA_DESC + ")V");
            if (owner.equals(TARGET_CLASS)
                    && name.equals("regulate")
                    && descriptor.equals("()V")) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                visitVarInsn(ALOAD, 0);
                super.visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "beforeDrawFrame",
                        "(Ljava/lang/Object;)V", false);
                return;
            }
            if (opcode == INVOKESPECIAL
                    && owner.equals(TARGET_CLASS)
                    && name.equals("drawCastle")
                    && descriptor.equals("(" + FAKEGRA_DESC + ")V")) {
                visitInsn(DUP2);
                visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onDrawBeforeCastle",
                        "(Ljava/lang/Object;" + FAKEGRA_DESC + ")V", false);
            }
            if (entityPass) {
                visitInsn(DUP2);
                visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onDrawUnder",
                        "(Ljava/lang/Object;" + FAKEGRA_DESC + ")V", false);
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (entityPass) {
                visitVarInsn(ALOAD, 0);
                visitVarInsn(ALOAD, 1);
                visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onDrawAfterEntity",
                        "(Ljava/lang/Object;" + FAKEGRA_DESC + ")V", false);
            }
        }
    }

    static class InsertReturnHook extends AdviceAdapter {
        private final String hookName;
        private final String hookDesc;
        private final boolean hasArg;

        InsertReturnHook(MethodVisitor mv, int access, String name, String descriptor,
                         String hookName, String hookDesc, boolean hasArg) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.hookName = hookName;
            this.hookDesc = hookDesc;
            this.hasArg = hasArg;
        }

        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label continueLabel = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            if (hasArg) {
                visitVarInsn(ALOAD, 1);
            }
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, hookName, hookDesc, false);
            visitJumpInsn(IFEQ, continueLabel);
            visitInsn(RETURN);
            visitLabel(continueLabel);
        }
    }

    static class WheelHook extends AdviceAdapter {
        WheelHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label nativeHandler = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onWheel",
                    "(Ljava/lang/Object;" + POINT_DESC + "I)Z", false);
            visitJumpInsn(IFEQ, nativeHandler);
            visitInsn(RETURN);
            visitLabel(nativeHandler);
        }
    }

    static class DrawLineupOverlayHook extends AdviceAdapter {
        DrawLineupOverlayHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitVarInsn(ILOAD, 3);
            visitVarInsn(FLOAD, 4);
            visitVarInsn(FLOAD, 5);
            visitVarInsn(ILOAD, 6);
            visitVarInsn(ILOAD, 7);
            visitMethodInsn(INVOKESTATIC,
                    "manualcontrol/crazy/base/SlingshotBaseFeature",
                    "drawNativeLineupOverlay",
                    "(Ljava/lang/Object;" + FAKEGRA_DESC + "IIFFZI)V",
                    false);
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitVarInsn(ILOAD, 3);
            visitVarInsn(FLOAD, 4);
            visitVarInsn(FLOAD, 5);
            visitVarInsn(ILOAD, 6);
            visitVarInsn(ILOAD, 7);
            visitMethodInsn(INVOKESTATIC,
                    "manualcontrol/crazy/unit/DiceSlotFeature",
                    "drawNativeLineupOverlay",
                    "(Ljava/lang/Object;" + FAKEGRA_DESC + "IIFFZI)V",
                    false);
        }
    }

    static class DrawTwoRowLineupOverlayHook extends AdviceAdapter {
        DrawTwoRowLineupOverlayHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitVarInsn(ILOAD, 3);
            visitVarInsn(FLOAD, 4);
            visitVarInsn(FLOAD, 5);
            visitVarInsn(FLOAD, 6);
            visitMethodInsn(INVOKESTATIC,
                    "manualcontrol/crazy/base/SlingshotBaseFeature",
                    "drawNativeTwoRowLineupOverlay",
                    "(Ljava/lang/Object;" + FAKEGRA_DESC + "IIFFF)V",
                    false);
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitVarInsn(ILOAD, 3);
            visitVarInsn(FLOAD, 4);
            visitVarInsn(FLOAD, 5);
            visitVarInsn(FLOAD, 6);
            visitMethodInsn(INVOKESTATIC,
                    "manualcontrol/crazy/unit/DiceSlotFeature",
                    "drawNativeTwoRowLineupOverlay",
                    "(Ljava/lang/Object;" + FAKEGRA_DESC + "IIFFF)V",
                    false);
        }
    }

    static class InsertEndHook extends AdviceAdapter {
        private final String hookName;
        private final String hookDesc;

        InsertEndHook(MethodVisitor mv, int access, String name, String descriptor,
                      String hookName, String hookDesc) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.hookName = hookName;
            this.hookDesc = hookDesc;
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, hookName, hookDesc, false);
        }
    }
}
