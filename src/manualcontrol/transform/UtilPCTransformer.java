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

public final class UtilPCTransformer implements ClassFileTransformer {
    private static final String TARGET = "utilpc/UtilPC";
    private static final String HOOK =
            "manualcontrol/custommap/CustomMapBackgroundHooks";
    private static final String ICON_DESC = "Ljavax/swing/ImageIcon;";
    private static final String GET_BG_DESC =
            "(Lcommon/util/pack/Background;II)" + ICON_DESC;

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
                    if ("getBg".equals(name) && GET_BG_DESC.equals(descriptor))
                        return new PreviewHook(mv, access, name, descriptor);
                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);
            Logger.log("*** PATCHED " + TARGET
                    + " (Custom Stage Background preview) ***");
            return writer.toByteArray();
        } catch (Throwable t) {
            Logger.err("Failed to patch " + TARGET, t);
            return null;
        }
    }

    private static final class PreviewHook extends AdviceAdapter {
        PreviewHook(MethodVisitor mv, int access, String name,
                    String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            Label nativePath = new Label();
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ILOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitMethodInsn(INVOKESTATIC, HOOK, "previewIcon",
                    "(Ljava/lang/Object;II)" + ICON_DESC, false);
            visitInsn(DUP);
            visitJumpInsn(IFNULL, nativePath);
            visitInsn(ARETURN);
            visitLabel(nativePath);
            visitInsn(POP);
        }
    }
}
