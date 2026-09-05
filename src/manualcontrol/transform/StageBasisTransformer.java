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

public class StageBasisTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "common/battle/StageBasis";
    private static final String HOOKS_CLASS = "manualcontrol/crazy/CrazyStageHooks";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cr.accept(new Patcher(cw), ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (crazy stage hooks) ***");
            return cw.toByteArray();
        } catch (Throwable t) {
            Logger.err("Failed to patch " + TARGET_CLASS, t);
            return null;
        }
    }

    static class Patcher extends ClassVisitor {
        Patcher(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (name.equals("update") && descriptor.equals("()V")) {
                return new UpdateHook(mv, access, name, descriptor);
            }
            if (name.equals("updateAnimation") && descriptor.equals("()V")) {
                return new AnimationUpdateHook(mv, access, name, descriptor);
            }
            if (name.equals("act_spawn") && descriptor.equals("(IIZ)Z")) {
                return new SpawnHook(mv, access, name, descriptor);
            }
            if (name.equals("entityCount") && descriptor.equals("(I)I")) {
                return new EntityCountHook(mv, access, name, descriptor);
            }
            if (name.equals("entityCount") && descriptor.equals("(II)I")) {
                return new EntityCountGroupHook(mv, access, name, descriptor);
            }
            if (name.equals("entityCountRar") && descriptor.equals("(I)I")) {
                return new EntityCountRarHook(mv, access, name, descriptor);
            }
            if (name.equals("inRange") && descriptor.equals("(IIFFZ)Ljava/util/List;")) {
                return new InRangeHook(mv, access, name, descriptor);
            }
            return mv;
        }
    }

    static class UpdateHook extends AdviceAdapter {
        UpdateHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label continueLabel = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onStageUpdate",
                    "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, continueLabel);
            visitInsn(RETURN);
            visitLabel(continueLabel);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
            if (opcode == PUTFIELD && TARGET_CLASS.equals(owner)
                    && "maxMoney".equals(name) && "I".equals(descriptor)) {
                visitVarInsn(ALOAD, 0);
                visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "overrideMaxMoney",
                        "(Ljava/lang/Object;)V", false);
            }
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != RETURN) return;
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "afterStageUpdate",
                    "(Ljava/lang/Object;)V", false);
        }
    }

    static class AnimationUpdateHook extends AdviceAdapter {
        AnimationUpdateHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label continueLabel = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onStageAnimationUpdate",
                    "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, continueLabel);
            visitInsn(RETURN);
            visitLabel(continueLabel);
        }
    }

    static class SpawnHook extends AdviceAdapter {
        SpawnHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label continueLabel = new org.objectweb.asm.Label();
            org.objectweb.asm.Label failLabel = new org.objectweb.asm.Label();
            org.objectweb.asm.Label successLabel = new org.objectweb.asm.Label();
            int decision = newLocal(org.objectweb.asm.Type.INT_TYPE);
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ILOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitVarInsn(ILOAD, 3);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "beforeSpawn",
                    "(Ljava/lang/Object;IIZ)I", false);
            visitVarInsn(ISTORE, decision);
            visitVarInsn(ILOAD, decision);
            visitJumpInsn(IFLT, continueLabel);
            visitVarInsn(ILOAD, decision);
            visitJumpInsn(IFEQ, failLabel);
            visitJumpInsn(GOTO, successLabel);
            visitLabel(failLabel);
            visitInsn(ICONST_0);
            visitInsn(IRETURN);
            visitLabel(successLabel);
            visitInsn(ICONST_1);
            visitInsn(IRETURN);
            visitLabel(continueLabel);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != IRETURN) return;
            visitInsn(DUP);
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ILOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitVarInsn(ILOAD, 3);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onSpawn",
                    "(ZLjava/lang/Object;IIZ)V", false);
        }
    }

    static class EntityCountHook extends AdviceAdapter {
        EntityCountHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ILOAD, 1);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "entityCount",
                    "(Ljava/lang/Object;I)I", false);
            visitInsn(IRETURN);
        }
    }

    static class EntityCountGroupHook extends AdviceAdapter {
        EntityCountGroupHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ILOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "entityCountGroup",
                    "(Ljava/lang/Object;II)I", false);
            visitInsn(IRETURN);
        }
    }

    static class EntityCountRarHook extends AdviceAdapter {
        EntityCountRarHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ILOAD, 1);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "entityCountRar",
                    "(Ljava/lang/Object;I)I", false);
            visitInsn(IRETURN);
        }
    }

    static class InRangeHook extends AdviceAdapter {
        InRangeHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ARETURN) return;
            visitInsn(DUP);
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ILOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitVarInsn(FLOAD, 3);
            visitVarInsn(FLOAD, 4);
            visitVarInsn(ILOAD, 5);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onInRange",
                    "(Ljava/util/List;Ljava/lang/Object;IIFFZ)V", false);
        }
    }
}
