package manualcontrol.transform;

import manualcontrol.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class AtkModelUnitTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "common/battle/attack/AtkModelUnit";
    private static final String HOOKS_CLASS = "manualcontrol/crazy/CrazyAttackHooks";
    private static final String SUMMON_DESC =
            "(Lcommon/util/Data$Proc$SUMMON;Lcommon/battle/entity/Entity;Ljava/lang/Object;I)V";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cr.accept(new Patcher(cw), ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (summon attach to part) ***");
            return cw.toByteArray();
        } catch (Throwable t) {
            Logger.err("Failed to patch " + TARGET_CLASS, t);
            return null;
        }
    }

    private static final class Patcher extends ClassVisitor {
        Patcher(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("summon".equals(name) && SUMMON_DESC.equals(descriptor)) {
                return new SummonHook(mv, access, name, descriptor);
            }
            return mv;
        }
    }

    private static final class SummonHook extends AdviceAdapter {
        SummonHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            Label cont = new Label();
            loadThis();
            loadArg(0);
            loadArg(1);
            loadArg(2);
            loadArg(3);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onSummonAttach",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)Z", false);
            visitJumpInsn(IFEQ, cont);
            visitInsn(RETURN);
            visitLabel(cont);
        }
    }
}
