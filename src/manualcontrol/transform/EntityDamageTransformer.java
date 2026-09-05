package manualcontrol.transform;

import manualcontrol.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class EntityDamageTransformer implements ClassFileTransformer {

    private static final String TARGET_ENTITY = "common/battle/entity/Entity";
    private static final String TARGET_ECASTLE = "common/battle/entity/ECastle";
    private static final String TARGET_EENEMY = "common/battle/entity/EEnemy";
    private static final String AB_ENTITY = "common/battle/entity/AbEntity";
    private static final String HOOKS_CLASS = "manualcontrol/crazy/CrazyAttackHooks";
    private static final String ATTACK_DESC = "Lcommon/battle/attack/AttackAb;";
    private static final String KILL_MODE_DESC = "Lcommon/battle/entity/Entity$KillMode;";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_ENTITY.equals(className) && !TARGET_ECASTLE.equals(className)
                && !TARGET_EENEMY.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cr.accept(new Patcher(cw, className), ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + className + " (crazy damage hooks) ***");
            return cw.toByteArray();
        } catch (Throwable t) {
            Logger.err("Failed to patch " + className + " damage", t);
            return null;
        }
    }

    static class Patcher extends ClassVisitor {
        private final String className;

        Patcher(ClassVisitor cv, String className) {
            super(Opcodes.ASM9, cv);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (name.equals("damaged") && descriptor.equals("(" + ATTACK_DESC + ")Z")) {
                return new DamagedHook(mv, access, name, descriptor, true);
            }
            if (TARGET_ECASTLE.equals(className) && name.equals("damaged")
                    && descriptor.equals("(" + ATTACK_DESC + ")V")) {
                return new DamagedHook(mv, access, name, descriptor, false);
            }
            if (TARGET_EENEMY.equals(className) && name.equals("kill")
                    && descriptor.equals("(" + KILL_MODE_DESC + ")V")) {
                return new EnemyKillHook(mv, access, name, descriptor);
            }
            return mv;
        }
    }

    static class DamagedHook extends AdviceAdapter {
        private int healthBeforeLocal = -1;
        private int pendingBeforeLocal = -1;
        private final boolean returnsBoolean;

        DamagedHook(MethodVisitor mv, int access, String name, String descriptor, boolean returnsBoolean) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.returnsBoolean = returnsBoolean;
        }

        @Override
        protected void onMethodEnter() {
            healthBeforeLocal = newLocal(org.objectweb.asm.Type.LONG_TYPE);
            visitVarInsn(ALOAD, 0);
            visitFieldInsn(GETFIELD, AB_ENTITY, "health", "J");
            visitVarInsn(LSTORE, healthBeforeLocal);
            pendingBeforeLocal = newLocal(org.objectweb.asm.Type.LONG_TYPE);
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "pendingDamage",
                    "(Ljava/lang/Object;)J", false);
            visitVarInsn(LSTORE, pendingBeforeLocal);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (returnsBoolean) {
                if (opcode != IRETURN) return;
                visitInsn(DUP);
            } else {
                if (opcode != RETURN) return;
                visitInsn(ICONST_1);
            }
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitVarInsn(LLOAD, healthBeforeLocal);
            visitVarInsn(LLOAD, pendingBeforeLocal);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "afterEntityDamaged",
                    "(ZLjava/lang/Object;Ljava/lang/Object;JJ)V", false);
        }
    }

    static class EnemyKillHook extends AdviceAdapter {
        EnemyKillHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "beforeEnemyKill",
                    "(Ljava/lang/Object;)V", false);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != RETURN) return;
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "afterEnemyKill",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        }
    }
}
