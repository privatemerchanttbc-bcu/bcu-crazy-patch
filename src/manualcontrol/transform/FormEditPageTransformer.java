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

public final class FormEditPageTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "page/info/edit/FormEditPage";
    private static final String HOOKS_CLASS = "manualcontrol/crazy/unit/SpecialSummonEditorHooks";
    private static final String CTOR_DESC =
            "(Lpage/Page;Lcommon/pack/PackData$UserPack;Lcommon/util/unit/Form;)V";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cr.accept(new Patcher(cw), ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (special summon button) ***");
            return cw.toByteArray();
        } catch (Throwable t) {
            Logger.err("Failed to patch " + TARGET_CLASS, t);
            return null;
        }
    }

    private static final class Patcher extends ClassVisitor {
        Patcher(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("<init>".equals(name) && CTOR_DESC.equals(descriptor)) {
                return new BuiltHook(mv, access, name, descriptor);
            }
            if ("resized".equals(name) && "(II)V".equals(descriptor)) {
                return new ResizedHook(mv, access, name, descriptor);
            }
            return mv;
        }
    }

    private static final class BuiltHook extends AdviceAdapter {
        BuiltHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            loadThis();
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onFormEditPageBuilt",
                    "(Ljava/lang/Object;)V", false);
        }
    }

    private static final class ResizedHook extends AdviceAdapter {
        ResizedHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            loadThis();
            loadArg(0);
            loadArg(1);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onFormEditPageResized",
                    "(Ljava/lang/Object;II)V", false);
        }
    }
}
