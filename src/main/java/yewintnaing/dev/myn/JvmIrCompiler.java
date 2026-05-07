package yewintnaing.dev.myn;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class JvmIrCompiler implements Opcodes {
    void compile(MynIr.Module module, String className, Path outputDir) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String internalName = className.replace('.', '/');

        cw.visit(V23, ACC_PUBLIC | ACC_FINAL, internalName, null, "java/lang/Object", null);
        emitConstructor(cw);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        mv.visitCode();

        CodegenContext ctx = new CodegenContext(mv, module.locals());
        for (MynIr.Instr instr : module.instructions()) {
            compileInstr(instr, ctx);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        Files.createDirectories(outputDir);
        Files.write(outputDir.resolve(className + ".class"), cw.toByteArray());
    }

    private static void emitConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void compileInstr(MynIr.Instr instr, CodegenContext ctx) {
        switch (instr) {
            case MynIr.Instr.Const(String target, Object value, MynType type) -> {
                pushLiteral(value, type, ctx.mv);
                storeName(target, jvmType(type), ctx);
            }
            case MynIr.Instr.Move(String target, String source) -> {
                LocalSlot sourceSlot = ctx.require(source);
                loadLocal(sourceSlot, ctx.mv);
                storeName(target, sourceSlot.type(), ctx);
            }
            case MynIr.Instr.Unary(String target, String op, String operand, MynType type) ->
                    compileUnary(target, op, operand, type, ctx);
            case MynIr.Instr.Binary(String target, String left, String op, String right, MynType type) ->
                    compileBinary(target, left, op, right, type, ctx);
            case MynIr.Instr.CallBuiltin(String target, String name, List<String> args, MynType type) ->
                    compileBuiltin(target, name, args, type, ctx);
            case MynIr.Instr.Label(String name) -> ctx.label(name);
            case MynIr.Instr.Jump(String targetLabel) -> ctx.mv.visitJumpInsn(GOTO, ctx.labelRef(targetLabel));
            case MynIr.Instr.BranchFalse(String conditionTemp, String targetLabel) -> {
                LocalSlot slot = ctx.require(conditionTemp);
                if (slot.type() != JvmType.BOOLEAN) {
                    throw error("Branch condition must be Boolean but was " + slot.type().sourceName);
                }
                loadLocal(slot, ctx.mv);
                ctx.mv.visitJumpInsn(IFEQ, ctx.labelRef(targetLabel));
            }
        }
    }

    private void compileUnary(String target, String op, String operand, MynType type, CodegenContext ctx) {
        LocalSlot source = ctx.require(operand);
        loadLocal(source, ctx.mv);
        switch (op) {
            case "-" -> {
                requireType(source, JvmType.INT, "unary '-'");
                ctx.mv.visitInsn(LNEG);
                storeName(target, JvmType.INT, ctx);
            }
            case "!" -> {
                requireType(source, JvmType.BOOLEAN, "unary '!'");
                Label trueLabel = new Label();
                Label endLabel = new Label();
                ctx.mv.visitJumpInsn(IFEQ, trueLabel);
                ctx.mv.visitInsn(ICONST_0);
                ctx.mv.visitJumpInsn(GOTO, endLabel);
                ctx.mv.visitLabel(trueLabel);
                ctx.mv.visitInsn(ICONST_1);
                ctx.mv.visitLabel(endLabel);
                storeName(target, JvmType.BOOLEAN, ctx);
            }
            default -> throw error("Unsupported unary operator in IR compiler: " + op);
        }
    }

    private void compileBinary(String target, String left, String op, String right, MynType type, CodegenContext ctx) {
        if ("&&".equals(op) || "||".equals(op)) {
            compileLogical(target, left, op, right, ctx);
            return;
        }

        LocalSlot leftSlot = ctx.require(left);
        LocalSlot rightSlot = ctx.require(right);
        JvmType resultType = jvmType(type);

        if ("+".equals(op) && resultType == JvmType.STRING) {
            compileStringConcat(target, leftSlot, rightSlot, ctx);
            return;
        }

        loadLocal(leftSlot, ctx.mv);
        loadLocal(rightSlot, ctx.mv);

        switch (op) {
            case "+" -> {
                requireType(leftSlot, JvmType.INT, "left operand of '+'");
                requireType(rightSlot, JvmType.INT, "right operand of '+'");
                ctx.mv.visitInsn(LADD);
                storeName(target, JvmType.INT, ctx);
            }
            case "-" -> {
                requireType(leftSlot, JvmType.INT, "left operand of '-'");
                requireType(rightSlot, JvmType.INT, "right operand of '-'");
                ctx.mv.visitInsn(LSUB);
                storeName(target, JvmType.INT, ctx);
            }
            case "*" -> {
                requireType(leftSlot, JvmType.INT, "left operand of '*'");
                requireType(rightSlot, JvmType.INT, "right operand of '*'");
                ctx.mv.visitInsn(LMUL);
                storeName(target, JvmType.INT, ctx);
            }
            case "/" -> {
                requireType(leftSlot, JvmType.INT, "left operand of '/'");
                requireType(rightSlot, JvmType.INT, "right operand of '/'");
                ctx.mv.visitInsn(LDIV);
                storeName(target, JvmType.INT, ctx);
            }
            case "%" -> {
                requireType(leftSlot, JvmType.INT, "left operand of '%'");
                requireType(rightSlot, JvmType.INT, "right operand of '%'");
                ctx.mv.visitInsn(LREM);
                storeName(target, JvmType.INT, ctx);
            }
            case "<", "<=", ">", ">=" -> {
                requireType(leftSlot, JvmType.INT, "left operand of '" + op + "'");
                requireType(rightSlot, JvmType.INT, "right operand of '" + op + "'");
                compileLongComparison(op, ctx.mv);
                storeName(target, JvmType.BOOLEAN, ctx);
            }
            case "==", "!=" -> {
                compileEquality(leftSlot, rightSlot, op, ctx.mv);
                storeName(target, JvmType.BOOLEAN, ctx);
            }
            default -> throw error("Unsupported binary operator in IR compiler: " + op);
        }
    }

    private void compileLogical(String target, String left, String op, String right, CodegenContext ctx) {
        LocalSlot leftSlot = ctx.require(left);
        LocalSlot rightSlot = ctx.require(right);
        requireType(leftSlot, JvmType.BOOLEAN, "left operand of '" + op + "'");
        requireType(rightSlot, JvmType.BOOLEAN, "right operand of '" + op + "'");

        Label shortCircuit = new Label();
        Label end = new Label();

        loadLocal(leftSlot, ctx.mv);
        if ("&&".equals(op)) {
            ctx.mv.visitJumpInsn(IFEQ, shortCircuit);
            loadLocal(rightSlot, ctx.mv);
            ctx.mv.visitJumpInsn(GOTO, end);
            ctx.mv.visitLabel(shortCircuit);
            ctx.mv.visitInsn(ICONST_0);
        } else {
            ctx.mv.visitJumpInsn(IFNE, shortCircuit);
            loadLocal(rightSlot, ctx.mv);
            ctx.mv.visitJumpInsn(GOTO, end);
            ctx.mv.visitLabel(shortCircuit);
            ctx.mv.visitInsn(ICONST_1);
        }
        ctx.mv.visitLabel(end);
        storeName(target, JvmType.BOOLEAN, ctx);
    }

    private void compileStringConcat(String target, LocalSlot left, LocalSlot right, CodegenContext ctx) {
        ctx.mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
        ctx.mv.visitInsn(DUP);
        ctx.mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        loadLocal(left, ctx.mv);
        appendStringified(left.type(), ctx.mv);
        loadLocal(right, ctx.mv);
        appendStringified(right.type(), ctx.mv);
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        storeName(target, JvmType.STRING, ctx);
    }

    private void compileBuiltin(String target, String name, List<String> args, MynType type, CodegenContext ctx) {
        if (args.size() != 1) {
            throw error("Builtin " + name + " expects 1 argument but got " + args.size());
        }
        LocalSlot arg = ctx.require(args.getFirst());
        JvmType resultType = jvmType(type);

        switch (name) {
            case "print", "println" -> {
                ctx.mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                loadLocal(arg, ctx.mv);
                pushStringValue(arg.type(), ctx.mv);
                ctx.mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", name, "(Ljava/lang/String;)V", false);
                storeUnit(target, ctx);
            }
            case "typeOf" -> {
                ctx.mv.visitLdcInsn(arg.type().sourceName);
                storeName(target, JvmType.STRING, ctx);
            }
            case "toString" -> {
                loadLocal(arg, ctx.mv);
                pushStringValue(arg.type(), ctx.mv);
                storeName(target, JvmType.STRING, ctx);
            }
            case "len" -> {
                requireType(arg, JvmType.STRING, "parameter 'value'");
                loadLocal(arg, ctx.mv);
                ctx.mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
                ctx.mv.visitInsn(I2L);
                storeName(target, JvmType.INT, ctx);
            }
            default -> throw error("Unsupported builtin in IR compiler: " + name);
        }

        if (resultType == JvmType.UNIT && !"print".equals(name) && !"println".equals(name)) {
            storeUnit(target, ctx);
        }
    }

    private static void pushLiteral(Object value, MynType type, MethodVisitor mv) {
        switch (type) {
            case INT -> mv.visitLdcInsn(value instanceof Double d ? d.longValue() : (Long) value);
            case STRING -> mv.visitLdcInsn((String) value);
            case BOOLEAN -> mv.visitInsn((Boolean) value ? ICONST_1 : ICONST_0);
            case UNIT -> {
            }
            default -> throw error("Unsupported literal type in IR compiler: " + type.displayName());
        }
    }

    private static void storeUnit(String target, CodegenContext ctx) {
        if (!target.startsWith("%t")) {
            return;
        }
        ctx.rememberUnitTemp(target);
    }

    private static void storeName(String name, JvmType type, CodegenContext ctx) {
        LocalSlot slot = ctx.defineIfAbsent(name, type);
        storeLocal(slot, ctx.mv);
    }

    private static void loadLocal(LocalSlot slot, MethodVisitor mv) {
        if (slot.type() == JvmType.UNIT) {
            return;
        }
        mv.visitVarInsn(slot.type().loadOpcode, slot.index());
    }

    private static void storeLocal(LocalSlot slot, MethodVisitor mv) {
        if (slot.type() == JvmType.UNIT) {
            return;
        }
        mv.visitVarInsn(slot.type().storeOpcode, slot.index());
    }

    private static void appendStringified(JvmType type, MethodVisitor mv) {
        pushStringValue(type, mv);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
    }

    private static void pushStringValue(JvmType type, MethodVisitor mv) {
        switch (type) {
            case STRING -> {
            }
            case INT -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false);
            case BOOLEAN -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Z)Ljava/lang/String;", false);
            case UNIT -> mv.visitLdcInsn("Unit");
        }
    }

    private static void compileLongComparison(String op, MethodVisitor mv) {
        mv.visitInsn(LCMP);
        Label trueLabel = new Label();
        Label endLabel = new Label();
        mv.visitJumpInsn(switch (op) {
            case "<" -> IFLT;
            case "<=" -> IFLE;
            case ">" -> IFGT;
            default -> IFGE;
        }, trueLabel);
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, endLabel);
        mv.visitLabel(trueLabel);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(endLabel);
    }

    private static void compileEquality(LocalSlot left, LocalSlot right, String op, MethodVisitor mv) {
        if (left.type() != right.type()) {
            throw error("Compiler requires both sides of '" + op + "' to have the same type");
        }

        Label trueLabel = new Label();
        Label endLabel = new Label();
        switch (left.type()) {
            case INT -> {
                mv.visitInsn(LCMP);
                mv.visitJumpInsn("==".equals(op) ? IFEQ : IFNE, trueLabel);
            }
            case BOOLEAN -> mv.visitJumpInsn("==".equals(op) ? IF_ICMPEQ : IF_ICMPNE, trueLabel);
            case STRING -> {
                mv.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "equals",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn("==".equals(op) ? IFNE : IFEQ, trueLabel);
            }
            default -> throw error("Unsupported equality type in IR compiler: " + left.type().sourceName);
        }
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, endLabel);
        mv.visitLabel(trueLabel);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(endLabel);
    }

    private static void requireType(LocalSlot slot, JvmType expected, String context) {
        if (slot.type() != expected) {
            throw error("Type mismatch for " + context + ": expected " + expected.sourceName + " but got " + slot.type().sourceName);
        }
    }

    private static JvmType jvmType(MynType type) {
        return switch (type) {
            case INT -> JvmType.INT;
            case BOOLEAN -> JvmType.BOOLEAN;
            case STRING -> JvmType.STRING;
            case UNIT -> JvmType.UNIT;
            default -> throw error("Unsupported Myn type in IR compiler: " + type.displayName());
        };
    }

    private static CompileError error(String message) {
        return new CompileError(message);
    }

    private enum JvmType {
        INT("Int", 2, LLOAD, LSTORE),
        BOOLEAN("Boolean", 1, ILOAD, ISTORE),
        STRING("String", 1, ALOAD, ASTORE),
        UNIT("Unit", 0, -1, -1);

        private final String sourceName;
        private final int slots;
        private final int loadOpcode;
        private final int storeOpcode;

        JvmType(String sourceName, int slots, int loadOpcode, int storeOpcode) {
            this.sourceName = sourceName;
            this.slots = slots;
            this.loadOpcode = loadOpcode;
            this.storeOpcode = storeOpcode;
        }
    }

    private record LocalSlot(int index, JvmType type) {
    }

    private static final class CodegenContext {
        private final MethodVisitor mv;
        private final Map<String, LocalSlot> locals = new HashMap<>();
        private final Map<String, Label> labels = new HashMap<>();
        private final Map<String, MynType> moduleLocals;
        private final Map<String, JvmType> unitTemps = new HashMap<>();
        private int nextSlot = 1; // slot 0 is String[] args

        private CodegenContext(MethodVisitor mv, Map<String, MynType> moduleLocals) {
            this.mv = mv;
            this.moduleLocals = moduleLocals;
        }

        LocalSlot require(String name) {
            LocalSlot slot = locals.get(name);
            if (slot != null) {
                return slot;
            }
            if (unitTemps.containsKey(name)) {
                return new LocalSlot(-1, JvmType.UNIT);
            }
            throw error("Unknown IR local: " + name);
        }

        LocalSlot defineIfAbsent(String name, JvmType fallbackType) {
            LocalSlot existing = locals.get(name);
            if (existing != null) {
                if (existing.type() != fallbackType) {
                    throw error("IR local '" + name + "' changed type from " + existing.type().sourceName + " to " + fallbackType.sourceName);
                }
                return existing;
            }

            JvmType type = moduleLocals.containsKey(name) ? jvmType(moduleLocals.get(name)) : fallbackType;
            if (type == JvmType.UNIT) {
                return new LocalSlot(-1, JvmType.UNIT);
            }

            LocalSlot slot = new LocalSlot(nextSlot, type);
            nextSlot += type.slots;
            locals.put(name, slot);
            return slot;
        }

        void rememberUnitTemp(String name) {
            unitTemps.put(name, JvmType.UNIT);
        }

        Label labelRef(String name) {
            return labels.computeIfAbsent(name, ignored -> new Label());
        }

        void label(String name) {
            mv.visitLabel(labelRef(name));
        }
    }
}
