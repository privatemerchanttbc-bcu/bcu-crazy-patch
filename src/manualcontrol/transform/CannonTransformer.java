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

public class CannonTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "common/battle/entity/Cannon";
    private static final String HOOKS_CLASS = "manualcontrol/crazy/CrazyCannonHooks";
    private static final String FAKEGRA_DESC = "Lcommon/system/fake/FakeGraphics;";
    private static final String POINT_DESC = "Lcommon/system/P;";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cr.accept(new Patcher(cw), ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (crazy cannon hooks) ***");
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
            if (name.equals("activate") && descriptor.equals("()V")) {
                return new ReturnIfTrue(mv, access, name, descriptor, "onActivate");
            }
            if (name.equals("update") && descriptor.equals("()V")) {
                return new ReturnIfTrue(mv, access, name, descriptor, "onUpdate");
            }
            if (name.equals("drawAtk") && descriptor.equals("(" + FAKEGRA_DESC + POINT_DESC + "F)V")) {
                return new ReturnIfTrue(mv, access, name, descriptor, "onDrawAtk");
            }
            if (name.equals("drawBase") && descriptor.equals("(" + FAKEGRA_DESC + POINT_DESC + "F)V")) {
                return new DrawBaseTransform(mv, access, name, descriptor);
            }
            return mv;
        }
    }

    static class ReturnIfTrue extends AdviceAdapter {
        private final String hook;

        ReturnIfTrue(MethodVisitor mv, int access, String name, String descriptor, String hook) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.hook = hook;
        }

        @Override
        protected void onMethodEnter() {
            org.objectweb.asm.Label cont = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, hook, "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(IFEQ, cont);
            visitInsn(RETURN);
            visitLabel(cont);
        }
    }

    static class DrawBaseTransform extends AdviceAdapter {
        private int transformLocal = -1;

        DrawBaseTransform(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            transformLocal = newLocal(org.objectweb.asm.Type.getType("Lcommon/system/fake/FakeTransform;"));
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitVarInsn(ALOAD, 2);
            visitVarInsn(FLOAD, 3);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "beforeDrawBase",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;F)Lcommon/system/fake/FakeTransform;", false);
            visitVarInsn(ASTORE, transformLocal);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 1);
            visitVarInsn(ALOAD, transformLocal);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "afterDrawBase",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitVarInsn(ALOAD, 2);
            visitVarInsn(FLOAD, 3);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "afterNativeDrawBase",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;F)V", false);
        }
    }
}
