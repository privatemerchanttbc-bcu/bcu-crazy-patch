package manualcontrol.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class PackNoticeTransformer implements ClassFileTransformer {

    private static final String OPTS = "main/Opts";
    private static final String MAINPAGE = "page/MainPage";
    private static final String HOOK = "manualcontrol/hooks/PackNoticeHook";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (className == null) return null;
        if (OPTS.equals(className)) {
            return patch(classfileBuffer, new OptsPatcher(null), OPTS);
        }
        if (MAINPAGE.equals(className)) {
            return patch(classfileBuffer, new MainPagePatcher(null), MAINPAGE);
        }
        return null;
    }

    private byte[] patch(byte[] buf, PatcherFactory factory, String name) {
        try {
            ClassReader cr = new ClassReader(buf);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = factory.create(cw);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
            manualcontrol.Logger.log("*** PATCHED " + name + " (pack-notice redirect) ***");
            return cw.toByteArray();
        } catch (Throwable t) {
            manualcontrol.Logger.err("Failed to patch " + name, t);
            return null;
        }
    }

    private interface PatcherFactory {
        ClassVisitor create(ClassVisitor cw);
    }

    static class OptsPatcher extends ClassVisitor implements PatcherFactory {
        OptsPatcher(ClassVisitor cv) { super(Opcodes.ASM9, cv); }
        @Override public ClassVisitor create(ClassVisitor cw) { return new OptsPatcher(cw); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String sig, String[] ex) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            if (name.equals("errOnce")
                    && desc.equals("(Ljava/lang/String;Ljava/lang/String;Z)V")) {
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override
                    protected void onMethodEnter() {
                        Label cont = new Label();
                        loadArg(0);
                        loadArg(1);
                        visitMethodInsn(INVOKESTATIC, HOOK, "onErrOnce",
                                "(Ljava/lang/String;Ljava/lang/String;)Z", false);
                        visitJumpInsn(IFEQ, cont);
                        visitInsn(RETURN);
                        visitLabel(cont);
                    }
                };
            }
            return mv;
        }
    }

    static class MainPagePatcher extends ClassVisitor implements PatcherFactory {
        MainPagePatcher(ClassVisitor cv) { super(Opcodes.ASM9, cv); }
        @Override public ClassVisitor create(ClassVisitor cw) { return new MainPagePatcher(cw); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String sig, String[] ex) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            if (name.equals("refrTips") && desc.equals("()V")) {
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override
                    protected void onMethodExit(int opcode) {
                        if (opcode == ATHROW) return;
                        loadThis();
                        visitMethodInsn(INVOKESTATIC, HOOK, "afterRefrTips",
                                "(Ljava/lang/Object;)V", false);
                    }
                };
            }
            return mv;
        }
    }
}
