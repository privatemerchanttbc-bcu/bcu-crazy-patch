package manualcontrol.perf;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class FrameTimeTransformer implements ClassFileTransformer {
    public static final String PAINTER = "page/battle/BattleBox$BBPainter";
    public static final String FIELD = "common/battle/BattleField";
    private static final String HOOK = "manualcontrol/perf/FrameTimeProfiler";

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redef,
                                     ProtectionDomain domain, byte[] bytes) {
        if (!PAINTER.equals(name) && !FIELD.equals(name)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            final boolean[] alreadyPatched = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                            if (HOOK.equals(owner)) alreadyPatched[0] = true;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (alreadyPatched[0]) return null;
            final boolean draw = PAINTER.equals(name);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            final int[] matched = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(a, n, d, s, e);
                    if (!(draw ? "draw".equals(n) && "(Lcommon/system/fake/FakeGraphics;)V".equals(d)
                            : "update".equals(n) && "()V".equals(d))) return mv;
                    matched[0]++;
                    return new Timing(mv, a, n, d, draw);
                }
            }, ClassReader.EXPAND_FRAMES);
            if (matched[0] != 1) throw new IllegalStateException("timing method count=" + matched[0]);
            return writer.toByteArray();
        } catch (Throwable error) {
            FrameTimeProfiler.disable("transform " + name, error);
            return null;
        }
    }

    private static final class Timing extends AdviceAdapter {
        final boolean draw;
        final Label start = new Label(), end = new Label(), handler = new Label();
        int stamp;
        Timing(MethodVisitor mv, int access, String name, String desc, boolean draw) {
            super(Opcodes.ASM9, mv, access, name, desc); this.draw = draw;
        }
        private void owner() {
            mv.visitVarInsn(ALOAD, 0);
            if (draw) mv.visitFieldInsn(GETFIELD, PAINTER, "bf", "Lcommon/battle/BattleField;");
        }
        @Override protected void onMethodEnter() {
            stamp = newLocal(Type.LONG_TYPE);
            owner();
            mv.visitMethodInsn(INVOKESTATIC, HOOK, draw ? "beginDraw" : "beginUpdate", "(Ljava/lang/Object;)J", false);
            mv.visitVarInsn(LSTORE, stamp);
            mv.visitLabel(start);
        }
        private void finish(boolean failed) {
            owner(); mv.visitInsn(draw ? ICONST_1 : ICONST_2);
            mv.visitVarInsn(LLOAD, stamp); mv.visitInsn(failed ? ICONST_1 : ICONST_0);
            mv.visitMethodInsn(INVOKESTATIC, HOOK, "end", "(Ljava/lang/Object;IJZ)V", false);
        }
        @Override protected void onMethodExit(int opcode) {
            if (opcode == RETURN) finish(false);
        }
        @Override public void visitMaxs(int stack, int locals) {
            mv.visitLabel(end);
            mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            mv.visitLabel(handler);
            int exception = newLocal(Type.getType(Throwable.class));
            mv.visitVarInsn(ASTORE, exception);
            finish(true);
            mv.visitVarInsn(ALOAD, exception); mv.visitInsn(ATHROW);
            super.visitMaxs(stack, locals);
        }
    }
}
