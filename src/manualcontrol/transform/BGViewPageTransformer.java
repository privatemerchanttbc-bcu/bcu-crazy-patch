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

public final class BGViewPageTransformer implements ClassFileTransformer {
    private static final String TARGET = "page/view/BGViewPage";
    private static final String HOOK =
            "manualcontrol/custommap/CustomMapBackgroundHooks";

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain pd,
                            byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name,
                                                 String descriptor,
                                                 String signature,
                                                 String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name,
                            descriptor, signature, exceptions);
                    if ("<init>".equals(name))
                        return new PrepareHook(mv, access, name, descriptor);
                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET
                    + " (Custom Stage Background catalog) ***");
            return writer.toByteArray();
        } catch (Throwable t) {
            Logger.err("Failed to patch " + TARGET, t);
            return null;
        }
    }

    private static final class PrepareHook extends AdviceAdapter {
        PrepareHook(MethodVisitor mv, int access, String name,
                    String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitMethodInsn(INVOKESTATIC, HOOK, "prepareCatalog", "()V",
                    false);
        }
    }
}
