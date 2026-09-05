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

public class SniperTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "common/battle/entity/Sniper";
    private static final String HOOKS_CLASS = "manualcontrol/hooks/SniperManualHooks";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cr.accept(new Patcher(cw), ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (manual sniper update) ***");
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
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onSniperUpdate",
                    "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, continueLabel);
            visitInsn(RETURN);
            visitLabel(continueLabel);
        }
    }
}
