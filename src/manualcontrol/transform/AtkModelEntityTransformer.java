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

public final class AtkModelEntityTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "common/battle/attack/AtkModelEntity";
    private static final String HOOKS_CLASS = "manualcontrol/crazy/CrazyAttackHooks";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cr.accept(new Patcher(cw), ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (conjure attack direction) ***");
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
            if ("getDire".equals(name) && "()I".equals(descriptor)) {
                return new DirectionHook(mv, access, name, descriptor);
            }
            if ("invokeLater".equals(name)
                    && "(Lcommon/battle/attack/AttackAb;Lcommon/battle/entity/Entity;)V".equals(descriptor)) {
                return new InvokeLaterHook(mv, access, name, descriptor);
            }
            if ("setProc".equals(name) && "(ILcommon/util/Data$Proc;)V".equals(descriptor)) {
                return new SetProcHook(mv, access, name, descriptor);
            }
            return mv;
        }
    }

    private static final class InvokeLaterHook extends AdviceAdapter {
        InvokeLaterHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            loadThis();
            loadArg(0);
            loadArg(1);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onInvokeLater",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
        }
    }

    private static final class SetProcHook extends AdviceAdapter {
        SetProcHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            loadThis();
            loadArg(0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onSetProc",
                    "(Ljava/lang/Object;I)V", false);
        }
    }

    private static final class DirectionHook extends AdviceAdapter {
        DirectionHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != IRETURN) return;

            loadThis();
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "overrideAttackDirection",
                    "(ILjava/lang/Object;)I", false);
        }
    }
}
