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

public class BattleInfoPageTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "page/battle/BattleInfoPage";
    private static final String HOOKS_CLASS = "manualcontrol/hooks/BattleInfoHooks";
    private static final String MOUSE_EVENT_DESC = "Ljava/awt/event/MouseEvent;";
    private static final String PAGE_DESC = "Lpage/Page;";
    private static final String STAGE_DESC = "Lcommon/util/stage/Stage;";
    private static final String BASIS_LU_DESC = "Lcommon/battle/BasisLU;";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new Patcher(cw);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET_CLASS + " (mouse handlers + entity tables) ***");
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

            if (name.equals("mousePressed") && descriptor.equals("(" + MOUSE_EVENT_DESC + ")V")) {
                return new ConsumableHook(mv, access, name, descriptor, "onMousePressed");
            }
            if (name.equals("mouseDragged") && descriptor.equals("(" + MOUSE_EVENT_DESC + ")V")) {
                return new ConsumableHook(mv, access, name, descriptor, "onMouseDragged");
            }
            if (name.equals("mouseReleased") && descriptor.equals("(" + MOUSE_EVENT_DESC + ")V")) {
                return new ConsumableHook(mv, access, name, descriptor, "onMouseReleased");
            }
            if (name.equals("<init>")
                    && descriptor.equals("(" + PAGE_DESC + STAGE_DESC + "I" + BASIS_LU_DESC + "[I)V")) {
                return new BattleConstructorHook(mv, access, name, descriptor);
            }
            if (name.equals("callBack") && descriptor.equals("(Ljava/lang/Object;)V")) {
                return new CallBackHook(mv, access, name, descriptor);
            }
            if (name.equals("onTimer") && descriptor.equals("(I)V")) {
                return new EntityTableHook(mv, access, name, descriptor);
            }
            return mv;
        }
    }

    /** Inject the converted-enemy UI filter after the lists have been built,
     * immediately before the first EntityTable.setList call. */
    static class EntityTableHook extends AdviceAdapter {
        private boolean injected;

        EntityTableHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if (!injected
                    && "page/battle/EntityTable".equals(owner)
                    && "setList".equals(name)
                    && "(Ljava/util/List;)V".equals(descriptor)) {
                // onTimer's locals are: 3=enemy list, 4=enemy stats,
                // 5=unit list, 6=unit stats (the BCU 0.5.8.x layout).
                visitVarInsn(ALOAD, 3);
                visitVarInsn(ALOAD, 4);
                visitVarInsn(ALOAD, 5);
                visitVarInsn(ALOAD, 6);
                visitMethodInsn(INVOKESTATIC,
                        "manualcontrol/hooks/BattleInfoHooks",
                        "filterConvertedBattleLists",
                        "(Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;)V",
                        false);
                injected = true;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
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
            org.objectweb.asm.Label continueLabel = new org.objectweb.asm.Label();
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, hookName,
                    "(Ljava/lang/Object;Ljava/awt/event/MouseEvent;)Z", false);
            visitJumpInsn(IFEQ, continueLabel);
            visitInsn(RETURN);
            visitLabel(continueLabel);
        }
    }

    static class CallBackHook extends AdviceAdapter {
        CallBackHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 0);
            visitInsn(ICONST_1);
            visitFieldInsn(PUTFIELD, TARGET_CLASS, "backClicked", "Z");
        }
    }

    static class BattleConstructorHook extends AdviceAdapter {
        BattleConstructorHook(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 2);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "beforeBattleConstructed",
                    "(Ljava/lang/Object;)V", false);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            visitMethodInsn(INVOKESTATIC, HOOKS_CLASS, "onBattleConstructed",
                    "(Ljava/lang/Object;)V", false);
        }
    }
}
