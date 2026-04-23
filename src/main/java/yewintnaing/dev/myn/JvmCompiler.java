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

final class JvmCompiler implements Opcodes {
    void compile(List<Stmt> program, String className, Path outputDir) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String internalName = className.replace('.', '/');

        cw.visit(V23, ACC_PUBLIC | ACC_FINAL, internalName, null, "java/lang/Object", null);
        emitConstructor(cw);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        mv.visitCode();

        CodegenContext ctx = new CodegenContext(mv, internalName);
        for (Stmt stmt : program) {
            compileStmt(stmt, ctx);
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

    private void compileStmt(Stmt stmt, CodegenContext ctx) {
        switch (stmt) {
            case Stmt.Block(List<Stmt> statements) -> {
                ctx.pushScope();
                for (Stmt child : statements) {
                    compileStmt(child, ctx);
                }
                ctx.popScope();
            }
            case Stmt.Var v -> compileVar(v, ctx);
            case Stmt.If(Expr cond, Stmt thenBranch, Stmt elseBranch) -> compileIf(cond, thenBranch, elseBranch, ctx);
            case Stmt.While(Expr cond, Stmt body) -> compileWhile(cond, body, ctx);
            case Stmt.Expr(Expr expr) -> {
                ExprResult result = compileExpr(expr, ctx);
                popIfNeeded(result.type(), ctx.mv);
            }
            case Stmt.Return ignored -> throw error("Top-level return is not supported in compiled scripts");
            case Stmt.Func f -> throw error(at(f.name()) + " User-defined functions are not supported by the first JVM compiler milestone yet");
        }
    }

    private void compileVar(Stmt.Var varStmt, CodegenContext ctx) {
        String name = varStmt.name().lexeme();
        if (ctx.lookup(name) != null) {
            throw error(at(varStmt.name()) + " Variable already defined in this scope: " + name);
        }

        if (varStmt.typeAnn() != null && "Unit".equals(varStmt.typeAnn())) {
            throw error(at(varStmt.name()) + " Unit variables are not supported by the first JVM compiler milestone");
        }

        ExprResult init = varStmt.init() == null ? ExprResult.unit() : compileExpr(varStmt.init(), ctx);
        JvmType declaredType = toJvmType(varStmt.typeAnn(), varStmt.name());
        JvmType finalType = declaredType != null ? declaredType : init.type();

        if (finalType == JvmType.UNIT) {
            throw error(at(varStmt.name()) + " Variable '" + name + "' needs a non-Unit value in the first JVM compiler milestone");
        }
        if (declaredType != null && init.type() != JvmType.UNIT && init.type() != declaredType) {
            throw error(at(varStmt.name()) + " Type mismatch for variable '" + name + "': expected " + declaredType.sourceName + " but got " + init.type().sourceName);
        }
        if (init.type() == JvmType.UNIT && declaredType == null) {
            throw error(at(varStmt.name()) + " Cannot infer a type for variable '" + name + "' without an initializer");
        }

        LocalSlot slot = ctx.define(name, finalType, varStmt.mutable());
        storeLocal(slot, ctx.mv);
    }

    private void compileIf(Expr cond, Stmt thenBranch, Stmt elseBranch, CodegenContext ctx) {
        ExprResult condition = compileExpr(cond, ctx);
        requireType(condition, JvmType.BOOLEAN, "if condition");

        Label elseLabel = new Label();
        Label endLabel = new Label();
        ctx.mv.visitJumpInsn(IFEQ, elseLabel);
        compileStmt(thenBranch, ctx);
        ctx.mv.visitJumpInsn(GOTO, endLabel);
        ctx.mv.visitLabel(elseLabel);
        if (elseBranch != null) {
            compileStmt(elseBranch, ctx);
        }
        ctx.mv.visitLabel(endLabel);
    }

    private void compileWhile(Expr cond, Stmt body, CodegenContext ctx) {
        Label start = new Label();
        Label end = new Label();
        ctx.mv.visitLabel(start);
        ExprResult condition = compileExpr(cond, ctx);
        requireType(condition, JvmType.BOOLEAN, "while condition");
        ctx.mv.visitJumpInsn(IFEQ, end);
        compileStmt(body, ctx);
        ctx.mv.visitJumpInsn(GOTO, start);
        ctx.mv.visitLabel(end);
    }

    private ExprResult compileExpr(Expr expr, CodegenContext ctx) {
        return switch (expr) {
            case Expr.Literal(Object value) -> compileLiteral(value, ctx.mv);
            case Expr.Grouping(Expr inner) -> compileExpr(inner, ctx);
            case Expr.Var(Token name) -> compileVarRef(name, ctx);
            case Expr.Assign(Token name, Expr value) -> compileAssign(name, value, ctx);
            case Expr.Unary(String op, Expr right) -> compileUnary(op, right, ctx);
            case Expr.Binary(Expr left, String op, Expr right) -> compileBinary(left, op, right, ctx);
            case Expr.Call(Expr callee, Token paren, List<Expr> args) -> compileCall(callee, paren, args, ctx);
            case Expr.Prefix(String op, Expr target) -> compileIncDec(op, target, true, ctx);
            case Expr.Postfix(String op, Expr target) -> compileIncDec(op, target, false, ctx);
        };
    }

    private ExprResult compileLiteral(Object value, MethodVisitor mv) {
        if (value instanceof Double d) {
            mv.visitLdcInsn(d.longValue());
            return ExprResult.of(JvmType.INT);
        }
        if (value instanceof String s) {
            mv.visitLdcInsn(s);
            return ExprResult.of(JvmType.STRING);
        }
        if (value instanceof Boolean b) {
            mv.visitInsn(b ? ICONST_1 : ICONST_0);
            return ExprResult.of(JvmType.BOOLEAN);
        }
        return ExprResult.unit();
    }

    private ExprResult compileVarRef(Token name, CodegenContext ctx) {
        LocalSlot slot = ctx.lookup(name.lexeme());
        if (slot == null) {
            throw error(at(name) + " Undefined variable: " + name.lexeme());
        }
        loadLocal(slot, ctx.mv);
        return ExprResult.of(slot.type());
    }

    private ExprResult compileAssign(Token name, Expr value, CodegenContext ctx) {
        LocalSlot slot = ctx.lookup(name.lexeme());
        if (slot == null) {
            throw error(at(name) + " Undefined variable: " + name.lexeme());
        }
        if (!slot.mutable()) {
            throw error(at(name) + " cannot assign to immutable variable: " + name.lexeme());
        }

        ExprResult result = compileExpr(value, ctx);
        requireType(result, slot.type(), "variable '" + name.lexeme() + "'");
        dup(slot.type(), ctx.mv);
        storeLocal(slot, ctx.mv);
        return ExprResult.of(slot.type());
    }

    private ExprResult compileUnary(String op, Expr right, CodegenContext ctx) {
        ExprResult result = compileExpr(right, ctx);
        return switch (op) {
            case "-" -> {
                requireType(result, JvmType.INT, "unary '-'");
                ctx.mv.visitInsn(LNEG);
                yield ExprResult.of(JvmType.INT);
            }
            case "!" -> {
                requireType(result, JvmType.BOOLEAN, "unary '!'");
                Label trueLabel = new Label();
                Label endLabel = new Label();
                ctx.mv.visitJumpInsn(IFEQ, trueLabel);
                ctx.mv.visitInsn(ICONST_0);
                ctx.mv.visitJumpInsn(GOTO, endLabel);
                ctx.mv.visitLabel(trueLabel);
                ctx.mv.visitInsn(ICONST_1);
                ctx.mv.visitLabel(endLabel);
                yield ExprResult.of(JvmType.BOOLEAN);
            }
            default -> throw error("Unsupported unary operator in compiler: " + op);
        };
    }

    private ExprResult compileBinary(Expr left, String op, Expr right, CodegenContext ctx) {
        if ("&&".equals(op) || "||".equals(op)) {
            return compileLogical(left, op, right, ctx);
        }

        return switch (op) {
            case "+" -> compilePlus(left, right, ctx);
            case "-", "*", "/", "%" -> {
                ExprResult l = compileExpr(left, ctx);
                ExprResult r = compileExpr(right, ctx);
                requireType(l, JvmType.INT, "left operand of '" + op + "'");
                requireType(r, JvmType.INT, "right operand of '" + op + "'");
                ctx.mv.visitInsn(switch (op) {
                    case "-" -> LSUB;
                    case "*" -> LMUL;
                    case "/" -> LDIV;
                    default -> LREM;
                });
                yield ExprResult.of(JvmType.INT);
            }
            case "<", "<=", ">", ">=" -> {
                ExprResult l = compileExpr(left, ctx);
                ExprResult r = compileExpr(right, ctx);
                requireType(l, JvmType.INT, "left operand of '" + op + "'");
                requireType(r, JvmType.INT, "right operand of '" + op + "'");
                yield compileLongComparison(op, ctx.mv);
            }
            case "==", "!=" -> {
                ExprResult l = compileExpr(left, ctx);
                ExprResult r = compileExpr(right, ctx);
                yield compileEquality(l, r, op, ctx.mv);
            }
            default -> throw error("Unsupported binary operator in compiler: " + op);
        };
    }

    private ExprResult compileLogical(Expr left, String op, Expr right, CodegenContext ctx) {
        ExprResult l = compileExpr(left, ctx);
        requireType(l, JvmType.BOOLEAN, "left operand of '" + op + "'");

        Label shortCircuit = new Label();
        Label end = new Label();
        if ("&&".equals(op)) {
            ctx.mv.visitJumpInsn(IFEQ, shortCircuit);
            ExprResult r = compileExpr(right, ctx);
            requireType(r, JvmType.BOOLEAN, "right operand of '&&'");
            ctx.mv.visitJumpInsn(GOTO, end);
            ctx.mv.visitLabel(shortCircuit);
            ctx.mv.visitInsn(ICONST_0);
        } else {
            ctx.mv.visitJumpInsn(IFNE, shortCircuit);
            ExprResult r = compileExpr(right, ctx);
            requireType(r, JvmType.BOOLEAN, "right operand of '||'");
            ctx.mv.visitJumpInsn(GOTO, end);
            ctx.mv.visitLabel(shortCircuit);
            ctx.mv.visitInsn(ICONST_1);
        }
        ctx.mv.visitLabel(end);
        return ExprResult.of(JvmType.BOOLEAN);
    }

    private ExprResult compilePlus(Expr leftExpr, Expr rightExpr, CodegenContext ctx) {
        ExprResult left = compileExpr(leftExpr, ctx);
        ExprResult right = compileExpr(rightExpr, ctx);

        if (left.type() == JvmType.INT && right.type() == JvmType.INT) {
            ctx.mv.visitInsn(LADD);
            return ExprResult.of(JvmType.INT);
        }

        if (!left.type().isStringLike() && left.type() != JvmType.INT && left.type() != JvmType.BOOLEAN) {
            throw error("Unsupported '+' left operand type in compiler: " + left.type().sourceName);
        }
        if (!right.type().isStringLike() && right.type() != JvmType.INT && right.type() != JvmType.BOOLEAN) {
            throw error("Unsupported '+' right operand type in compiler: " + right.type().sourceName);
        }

        LocalSlot leftTmp = ctx.temp(left.type());
        LocalSlot rightTmp = ctx.temp(right.type());
        storeLocal(rightTmp, ctx.mv);
        storeLocal(leftTmp, ctx.mv);

        ctx.mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
        ctx.mv.visitInsn(DUP);
        ctx.mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        loadLocal(leftTmp, ctx.mv);
        appendStringified(left.type(), ctx.mv);
        loadLocal(rightTmp, ctx.mv);
        appendStringified(right.type(), ctx.mv);
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        ctx.releaseTemp(rightTmp);
        ctx.releaseTemp(leftTmp);
        return ExprResult.of(JvmType.STRING);
    }

    private ExprResult compileLongComparison(String op, MethodVisitor mv) {
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
        return ExprResult.of(JvmType.BOOLEAN);
    }

    private ExprResult compileEquality(ExprResult left, ExprResult right, String op, MethodVisitor mv) {
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
            default -> throw error("Unsupported equality type in compiler: " + left.type().sourceName);
        }
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, endLabel);
        mv.visitLabel(trueLabel);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(endLabel);
        return ExprResult.of(JvmType.BOOLEAN);
    }

    private ExprResult compileCall(Expr callee, Token paren, List<Expr> args, CodegenContext ctx) {
        if (!(callee instanceof Expr.Var(Token nameToken))) {
            throw error(at(paren) + " Only direct builtin calls are supported by the first JVM compiler milestone");
        }

        String name = nameToken.lexeme();
        return switch (name) {
            case "print", "println" -> compilePrintLike(name, args, paren, ctx);
            case "typeOf" -> compileTypeOf(args, paren, ctx);
            case "toString" -> compileToString(args, paren, ctx);
            case "len" -> compileLen(args, paren, ctx);
            default -> throw error(at(paren) + " Only builtins print, println, typeOf, toString, and len are supported in the first JVM compiler milestone");
        };
    }

    private ExprResult compilePrintLike(String name, List<Expr> args, Token token, CodegenContext ctx) {
        requireArity(name, args, 1, token);
        ctx.mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        ExprResult arg = compileExpr(args.getFirst(), ctx);
        pushStringValue(arg.type(), ctx.mv);
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", name, "(Ljava/lang/String;)V", false);
        return ExprResult.unit();
    }

    private ExprResult compileTypeOf(List<Expr> args, Token token, CodegenContext ctx) {
        requireArity("typeOf", args, 1, token);
        ExprResult arg = compileExpr(args.getFirst(), ctx);
        popIfNeeded(arg.type(), ctx.mv);
        ctx.mv.visitLdcInsn(arg.type().sourceName);
        return ExprResult.of(JvmType.STRING);
    }

    private ExprResult compileToString(List<Expr> args, Token token, CodegenContext ctx) {
        requireArity("toString", args, 1, token);
        ExprResult arg = compileExpr(args.getFirst(), ctx);
        pushStringValue(arg.type(), ctx.mv);
        return ExprResult.of(JvmType.STRING);
    }

    private ExprResult compileLen(List<Expr> args, Token token, CodegenContext ctx) {
        requireArity("len", args, 1, token);
        ExprResult arg = compileExpr(args.getFirst(), ctx);
        requireType(arg, JvmType.STRING, "parameter 'value'");
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        ctx.mv.visitInsn(I2L);
        return ExprResult.of(JvmType.INT);
    }

    private ExprResult compileIncDec(String op, Expr target, boolean prefix, CodegenContext ctx) {
        if (!(target instanceof Expr.Var(Token name))) {
            throw error("Increment/decrement target must be a variable");
        }
        LocalSlot slot = ctx.lookup(name.lexeme());
        if (slot == null) {
            throw error(at(name) + " Undefined variable: " + name.lexeme());
        }
        if (!slot.mutable()) {
            throw error(at(name) + " cannot assign to immutable variable: " + name.lexeme());
        }
        if (slot.type() != JvmType.INT) {
            throw error(at(name) + " " + (prefix ? "Prefix" : "Postfix") + " " + op + " can only be applied to Int");
        }

        loadLocal(slot, ctx.mv);
        if (!prefix) {
            ctx.mv.visitInsn(DUP2);
        }
        ctx.mv.visitLdcInsn(1L);
        ctx.mv.visitInsn("++".equals(op) ? LADD : LSUB);
        if (prefix) {
            ctx.mv.visitInsn(DUP2);
        }
        storeLocal(slot, ctx.mv);
        return ExprResult.of(JvmType.INT);
    }

    private static void requireArity(String name, List<Expr> args, int arity, Token token) {
        if (args.size() != arity) {
            throw error(at(token) + " arity mismatch: expected " + arity + " arguments but got " + args.size());
        }
    }

    private static void requireType(ExprResult actual, JvmType expected, String context) {
        if (actual.type() != expected) {
            throw error("Type mismatch for " + context + ": expected " + expected.sourceName + " but got " + actual.type().sourceName);
        }
    }

    private static JvmType toJvmType(String typeAnn, Token token) {
        if (typeAnn == null) return null;
        return switch (typeAnn) {
            case "Int" -> JvmType.INT;
            case "Boolean" -> JvmType.BOOLEAN;
            case "String" -> JvmType.STRING;
            case "Unit" -> JvmType.UNIT;
            default -> throw error(at(token) + " Unknown type annotation for compiler: " + typeAnn);
        };
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

    private static void appendStringified(JvmType type, MethodVisitor mv) {
        pushStringValue(type, mv);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
    }

    private static void loadLocal(LocalSlot slot, MethodVisitor mv) {
        mv.visitVarInsn(slot.type().loadOpcode, slot.index());
    }

    private static void storeLocal(LocalSlot slot, MethodVisitor mv) {
        mv.visitVarInsn(slot.type().storeOpcode, slot.index());
    }

    private static void dup(JvmType type, MethodVisitor mv) {
        mv.visitInsn(type.slots == 2 ? DUP2 : DUP);
    }

    private static void popIfNeeded(JvmType type, MethodVisitor mv) {
        if (type == JvmType.UNIT) {
            return;
        }
        mv.visitInsn(type.slots == 2 ? POP2 : POP);
    }

    private static CompileError error(String message) {
        return new CompileError(message);
    }

    private static String at(Token token) {
        return "[line " + token.line() + ", col " + token.col() + "]";
    }

    private record ExprResult(JvmType type) {
        static ExprResult of(JvmType type) {
            return new ExprResult(type);
        }

        static ExprResult unit() {
            return new ExprResult(JvmType.UNIT);
        }
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

        boolean isStringLike() {
            return this == STRING;
        }
    }

    private record LocalSlot(int index, JvmType type, boolean mutable) {
    }

    private static final class CodegenContext {
        private final MethodVisitor mv;
        @SuppressWarnings("unused")
        private final String owner;
        private int nextSlot = 1; // slot 0 is String[] args
        private final Map<String, LocalSlot> locals = new HashMap<>();
        private final List<Map<String, LocalSlot>> scopes = new java.util.ArrayList<>();

        private CodegenContext(MethodVisitor mv, String owner) {
            this.mv = mv;
            this.owner = owner;
            scopes.add(new HashMap<>());
        }

        void pushScope() {
            scopes.add(new HashMap<>());
        }

        void popScope() {
            Map<String, LocalSlot> scope = scopes.removeLast();
            for (String name : scope.keySet()) {
                locals.remove(name);
            }
        }

        LocalSlot define(String name, JvmType type, boolean mutable) {
            LocalSlot slot = new LocalSlot(nextSlot, type, mutable);
            nextSlot += type.slots;
            locals.put(name, slot);
            scopes.getLast().put(name, slot);
            return slot;
        }

        LocalSlot temp(JvmType type) {
            LocalSlot slot = new LocalSlot(nextSlot, type, true);
            nextSlot += type.slots;
            return slot;
        }

        void releaseTemp(LocalSlot slot) {
            // Temp slots are allocated in LIFO order only.
            if (slot.index() + slot.type().slots != nextSlot) {
                return;
            }
            nextSlot -= slot.type().slots;
        }

        LocalSlot lookup(String name) {
            return locals.get(name);
        }
    }
}
