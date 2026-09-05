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

public class AttackSimpleTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "common/battle/attack/AttackSimple";
    private static final String HOOKS_CLASS = "manualcontrol/crazy/CrazyAttackHooks";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cr.accept(new Patcher(cw), ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (crazy attack hooks) ***");
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
            if (name.equals("excuse") && descriptor.equals("()V")) {
                return new ExcuseHook(mv, access, name, descriptor);
            }
            if (name.equals("capture") && descriptor.equals("()V")) {
                return new CaptureHook(mv, access, name, descriptor);
            }
            return mv;
        }
    }

    static class CaptureHook extends AdviceAdapter {
        CaptureHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "beforeAttackCapture",
                    "(Ljava/lang/Object;)V", false);
        }

        @Override
        protected void onMethodExit(int opcode) {

            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "afterAttackCapture",
                    "(Ljava/lang/Object;)V", false);
        }
    }

    static class ExcuseHook extends AdviceAdapter {
        ExcuseHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onAttackExcuse",
                    "(Ljava/lang/Object;)V", false);
        }
    }
}
