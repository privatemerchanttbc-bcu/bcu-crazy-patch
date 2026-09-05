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

public class EntityTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "common/battle/entity/Entity";
    private static final String HOOKS_CLASS = "manualcontrol/hooks/EntityHooks";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new Patcher(cw);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (update / updateMove / update2) ***");
            return cw.toByteArray();
        } catch (Throwable t) {
            Logger.err("Failed to patch " + TARGET_CLASS, t);
            return null;
        }
    }

    static class Patcher extends ClassVisitor {
        Patcher(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            if (name.equals("update") && descriptor.equals("()V")) {
                return new SkipIfControlled(mv, access, name, descriptor);
            }

            if (name.equals("updateMove") && descriptor.equals("(F)V")) {
                return new SkipIfUpdateMove(mv, access, name, descriptor);
            }

            if (name.equals("update2") && descriptor.equals("()V")) {
                return new SkipIfHeldOrFalling(mv, access, name, descriptor);
            }

            if (name.equals("updateAnimation") && descriptor.equals("()V")) {
                return new SkipIfAnimDriven(mv, access, name, descriptor);
            }

            if (name.equals("checkTouch") && descriptor.equals("()Z")) {
                return new FilterCheckTouch(mv, access, name, descriptor);
            }
            return mv;
        }
    }

    static class FilterCheckTouch extends AdviceAdapter {
        FilterCheckTouch(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != IRETURN) return;

            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, "manualcontrol/crazy/CrazyAttackHooks",
                    "afterCheckTouch", "(ZLjava/lang/Object;)Z", false);
        }
    }

    static class SkipIfControlled extends AdviceAdapter {
        SkipIfControlled(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label cont = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "shouldSkipUpdate",
                    "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, cont);
            visitInsn(RETURN);
            visitLabel(cont);
        }
    }

    static class SkipIfUpdateMove extends AdviceAdapter {
        SkipIfUpdateMove(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label cont = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "shouldSkipUpdateMove",
                    "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, cont);
            visitInsn(RETURN);
            visitLabel(cont);
        }
    }

    static class SkipIfHeldOrFalling extends AdviceAdapter {
        SkipIfHeldOrFalling(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label cont = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "shouldSkipUpdate2",
                    "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, cont);
            visitInsn(RETURN);
            visitLabel(cont);
        }
    }

    static class SkipIfAnimDriven extends AdviceAdapter {
        SkipIfAnimDriven(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label cont = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "shouldSkipUpdateAnimation",
                    "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, cont);
            visitInsn(RETURN);
            visitLabel(cont);
        }
    }
}
