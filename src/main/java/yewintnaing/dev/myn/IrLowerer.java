package yewintnaing.dev.myn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class IrLowerer {
    MynIr.Module lower(List<Stmt> program, TypeChecker.TypeCheckResult types) {
        LoweringState state = new LoweringState(types.expressionTypes(), types.locals());
        for (Stmt stmt : program) {
            lowerStmt(stmt, state);
        }
        return new MynIr.Module(List.copyOf(state.instructions), Map.copyOf(types.locals()));
    }

    private void lowerStmt(Stmt stmt, LoweringState state) {
        switch (stmt) {
            case Stmt.Block(List<Stmt> statements) -> {
                for (Stmt child : statements) {
                    lowerStmt(child, state);
                }
            }
            case Stmt.Var v -> {
                if (v.init() == null) {
                    String unitTemp = state.nextTemp();
                    state.emit(new MynIr.Instr.Const(unitTemp, "Unit", MynType.UNIT));
                    state.emit(new MynIr.Instr.Move(v.name().lexeme(), unitTemp));
                    return;
                }
                String value = lowerExpr(v.init(), state);
                state.emit(new MynIr.Instr.Move(v.name().lexeme(), value));
            }
            case Stmt.If(Expr cond, Stmt thenBranch, Stmt elseBranch) -> {
                String condition = lowerExpr(cond, state);
                String elseLabel = state.nextLabel("else");
                String endLabel = state.nextLabel("ifend");
                state.emit(new MynIr.Instr.BranchFalse(condition, elseLabel));
                lowerStmt(thenBranch, state);
                state.emit(new MynIr.Instr.Jump(endLabel));
                state.emit(new MynIr.Instr.Label(elseLabel));
                if (elseBranch != null) {
                    lowerStmt(elseBranch, state);
                }
                state.emit(new MynIr.Instr.Label(endLabel));
            }
            case Stmt.While(Expr cond, Stmt body) -> {
                String startLabel = state.nextLabel("while");
                String endLabel = state.nextLabel("while_end");
                state.emit(new MynIr.Instr.Label(startLabel));
                String condition = lowerExpr(cond, state);
                state.emit(new MynIr.Instr.BranchFalse(condition, endLabel));
                lowerStmt(body, state);
                state.emit(new MynIr.Instr.Jump(startLabel));
                state.emit(new MynIr.Instr.Label(endLabel));
            }
            case Stmt.Expr(Expr expr) -> lowerExpr(expr, state);
            case Stmt.Return ignored -> throw new CompileError("IR lowering for return is not introduced yet");
            case Stmt.Func f -> throw new CompileError("IR lowering for user-defined functions is not introduced yet: " + f.name().lexeme());
        }
    }

    private String lowerExpr(Expr expr, LoweringState state) {
        return switch (expr) {
            case Expr.Literal(Object value) -> {
                String temp = state.nextTemp();
                state.emit(new MynIr.Instr.Const(temp, value, state.typeOf(expr)));
                yield temp;
            }
            case Expr.Grouping(Expr inner) -> lowerExpr(inner, state);
            case Expr.Var(Token name) -> name.lexeme();
            case Expr.Assign(Token name, Expr value) -> {
                String source = lowerExpr(value, state);
                state.emit(new MynIr.Instr.Move(name.lexeme(), source));
                yield name.lexeme();
            }
            case Expr.Unary(String op, Expr right) -> {
                String operand = lowerExpr(right, state);
                String temp = state.nextTemp();
                state.emit(new MynIr.Instr.Unary(temp, op, operand, state.typeOf(expr)));
                yield temp;
            }
            case Expr.Binary(Expr left, String op, Expr right) -> {
                String leftTemp = lowerExpr(left, state);
                String rightTemp = lowerExpr(right, state);
                String temp = state.nextTemp();
                state.emit(new MynIr.Instr.Binary(temp, leftTemp, op, rightTemp, state.typeOf(expr)));
                yield temp;
            }
            case Expr.Call(Expr callee, Token paren, List<Expr> args) -> {
                if (!(callee instanceof Expr.Var(Token name))) {
                    throw new CompileError("[line " + paren.line() + ", col " + paren.col() + "] Only direct builtin calls are lowered to IR right now");
                }
                List<String> loweredArgs = new ArrayList<>();
                for (Expr arg : args) {
                    loweredArgs.add(lowerExpr(arg, state));
                }
                String temp = state.nextTemp();
                state.emit(new MynIr.Instr.CallBuiltin(temp, name.lexeme(), loweredArgs, state.typeOf(expr)));
                yield temp;
            }
            case Expr.Prefix(String op, Expr target) -> lowerIncDec(op, target, true, state);
            case Expr.Postfix(String op, Expr target) -> lowerIncDec(op, target, false, state);
        };
    }

    private String lowerIncDec(String op, Expr target, boolean prefix, LoweringState state) {
        if (!(target instanceof Expr.Var(Token name))) {
            throw new CompileError("Increment/decrement target must be a variable");
        }
        String one = state.nextTemp();
        state.emit(new MynIr.Instr.Const(one, 1L, MynType.INT));
        String updated = state.nextTemp();
        state.emit(new MynIr.Instr.Binary(updated, name.lexeme(), "++".equals(op) ? "+" : "-", one, MynType.INT));
        state.emit(new MynIr.Instr.Move(name.lexeme(), updated));
        if (prefix) {
            return updated;
        }
        String previous = state.nextTemp();
        state.emit(new MynIr.Instr.Binary(previous, updated, "++".equals(op) ? "-" : "+", one, MynType.INT));
        return previous;
    }

    private static final class LoweringState {
        private final Map<Expr, MynType> expressionTypes;
        private final Map<String, MynType> locals;
        private final List<MynIr.Instr> instructions = new ArrayList<>();
        private int tempCounter = 0;
        private int labelCounter = 0;

        private LoweringState(Map<Expr, MynType> expressionTypes, Map<String, MynType> locals) {
            this.expressionTypes = expressionTypes;
            this.locals = locals;
        }

        String nextTemp() {
            return "%t" + tempCounter++;
        }

        String nextLabel(String prefix) {
            return prefix + "_" + labelCounter++;
        }

        void emit(MynIr.Instr instr) {
            instructions.add(instr);
        }

        MynType typeOf(Expr expr) {
            MynType type = expressionTypes.get(expr);
            if (type != null) {
                return type;
            }
            if (expr instanceof Expr.Var(Token name)) {
                return locals.getOrDefault(name.lexeme(), MynType.ANY);
            }
            return MynType.UNIT;
        }
    }
}
