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

public class ConfigPageTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "page/ConfigPage";
    private static final String HOOKS = "manualcontrol/fps/ConfigPageHooks";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cr.accept(new Patcher(cw), ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (fps selector) ***");
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
            if (name.equals("<init>") && descriptor.equals("(Lpage/Page;)V")) {
                return new BuiltHook(mv, access, name, descriptor);
            }
            if (name.equals("resized") && descriptor.equals("(II)V")) {
                return new ResizedHook(mv, access, name, descriptor);
            }
            return mv;
        }
    }

    static class BuiltHook extends AdviceAdapter {
        BuiltHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS, "onConfigPageBuilt",
                    "(Ljava/lang/Object;)V", false);
        }
    }

    static class ResizedHook extends AdviceAdapter {
        ResizedHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ILOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitMethodInsn(INVOKESTATIC, HOOKS, "onConfigPageResized",
                    "(Ljava/lang/Object;II)V", false);
        }
    }
}
