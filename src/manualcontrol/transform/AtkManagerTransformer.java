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

public class AtkManagerTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "common/battle/entity/Entity$AtkManager";
    private static final String EGG_HOOKS_CLASS = "manualcontrol/crazy/unit/EggPetFeature";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cr.accept(new Patcher(cw), ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (egg pet attack speed hooks) ***");
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
            if (name.equals("startAttack") && descriptor.equals("()V")) {
                return new ExitHook(mv, access, name, descriptor, "onAttackStarted");
            }
            if (name.equals("updateAttack") && descriptor.equals("()V")) {
                return new ExitHook(mv, access, name, descriptor, "onAttackUpdateFinished");
            }
            return mv;
        }
    }

    static class ExitHook extends AdviceAdapter {
        private final String hookName;

        ExitHook(MethodVisitor mv, int access, String name, String descriptor, String hookName) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.hookName = hookName;
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, EGG_HOOKS_CLASS, hookName,
                    "(Ljava/lang/Object;)V", false);
        }
    }
}
