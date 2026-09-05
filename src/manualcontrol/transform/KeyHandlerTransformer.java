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

public class KeyHandlerTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "page/KeyHandler";
    private static final String HOOKS_CLASS = "manualcontrol/hooks/KeyboardHooks";
    private static final String KEY_EVENT_DESC = "Ljava/awt/event/KeyEvent;";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new Patcher(cw);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (key handlers) ***");
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
            if (name.equals("keyPressed") && descriptor.equals("(" + KEY_EVENT_DESC + ")V")) {
                return new ConsumableHook(mv, access, name, descriptor, "onKeyPressed");
            }
            if (name.equals("keyReleased") && descriptor.equals("(" + KEY_EVENT_DESC + ")V")) {
                return new ConsumableHook(mv, access, name, descriptor, "onKeyReleased");
            }
            return mv;
        }
    }

    static class ConsumableHook extends AdviceAdapter {
        private final String hookName;
        ConsumableHook(MethodVisitor mv, int access, String name, String descriptor, String hookName) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.hookName = hookName;
        }

        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label cont = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, hookName,
                    "(Ljava/lang/Object;Ljava/awt/event/KeyEvent;)Z", false);
            visitJumpInsn(IFEQ, cont);
            visitInsn(RETURN);
            visitLabel(cont);
        }
    }
}
