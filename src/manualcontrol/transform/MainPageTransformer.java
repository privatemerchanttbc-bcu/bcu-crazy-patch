package manualcontrol.transform;

import manualcontrol.Logger;
import manualcontrol.OptionalFeatures;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class MainPageTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "page/MainPage";
    private static final String HOOKS_CLASS = "manualcontrol/adventure/AdventureHooks";
    private static final String UC_HOOKS_CLASS = "unitcreator/UnitCreatorHooks";
    private static final String MODES_HOOKS_CLASS = "manualcontrol/modes/core/ModesHooks";
    private static final String CUSTOM_MAP_HOOKS_CLASS = "manualcontrol/custommap/CustomMapHooks";
    private static final String ARENA_HOOKS_CLASS = "manualcontrol/arena/ArenaHooks";
    private static final String STARTUP_PROFILE_CLASS = "manualcontrol/StartupProfile";

    private static final boolean HAS_ADVENTURE = present(HOOKS_CLASS);
    private static final boolean HAS_UNIT_CREATOR = present(UC_HOOKS_CLASS);
    private static final boolean HAS_MODES = present(MODES_HOOKS_CLASS);
    private static final boolean HAS_CUSTOM_MAP = present(CUSTOM_MAP_HOOKS_CLASS);
    private static final boolean HAS_ARENA = present(ARENA_HOOKS_CLASS);

    private static boolean present(String internalName) {
        boolean found = OptionalFeatures.present(internalName.replace('/', '.'));
        if (!found) Logger.log("main page hook " + internalName + ": absent");
        return found;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new Patcher(cw);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (adventure button) ***");
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
            if (name.equals("<init>") && descriptor.equals("()V")) {
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
            emit(HOOKS_CLASS, HAS_ADVENTURE);
            emit(UC_HOOKS_CLASS, HAS_UNIT_CREATOR);
            emit(MODES_HOOKS_CLASS, HAS_MODES);
            emit(CUSTOM_MAP_HOOKS_CLASS, HAS_CUSTOM_MAP);
            emit(ARENA_HOOKS_CLASS, HAS_ARENA);
            visitMethodInsn(INVOKESTATIC, STARTUP_PROFILE_CLASS, "mainPageBuilt",
                    "()V", false);
        }

        private void emit(String owner, boolean available) {
            if (!available) return;
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, owner, "onMainPageBuilt",
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
            emit(HOOKS_CLASS, HAS_ADVENTURE);
            emit(UC_HOOKS_CLASS, HAS_UNIT_CREATOR);
            emit(MODES_HOOKS_CLASS, HAS_MODES);
            emit(CUSTOM_MAP_HOOKS_CLASS, HAS_CUSTOM_MAP);
            emit(ARENA_HOOKS_CLASS, HAS_ARENA);
        }

        private void emit(String owner, boolean available) {
            if (!available) return;
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ILOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitMethodInsn(INVOKESTATIC, owner, "onMainPageResized",
                    "(Ljava/lang/Object;II)V", false);
        }
    }
}
