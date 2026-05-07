package yewintnaing.dev.myn;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class TypeChecker {
    TypeCheckResult check(List<Stmt> program, Resolver.Resolution resolution) {
        TypeState state = new TypeState(resolution);
        for (Stmt stmt : program) {
            checkStmt(stmt, state);
        }
        return new TypeCheckResult(state.exprTypes, state.locals);
    }

    private void checkStmt(Stmt stmt, TypeState state) {
        switch (stmt) {
            case Stmt.Block(List<Stmt> statements) -> {
                state.pushScope();
                for (Stmt child : statements) {
                    checkStmt(child, state);
                }
                state.popScope();
            }
            case Stmt.Var v -> {
                MynType initType = v.init() == null ? MynType.UNIT : checkExpr(v.init(), state);
                MynType declared = MynType.fromAnnotation(v.typeAnn());
                MynType actual = declared != null ? declared : initType;

                if (actual == MynType.UNIT && declared == null) {
                    throw new CompileError(at(v.name()) + " Cannot infer a type for variable '" + v.name().lexeme() + "' without an initializer");
                }
                if (declared != null && initType != MynType.UNIT && !assignable(declared, initType)) {
                    throw mismatch(v.name(), "variable '" + v.name().lexeme() + "'", declared, initType);
                }

                state.define(v.name(), actual, v.mutable(), false);
            }
            case Stmt.Func f -> {
                state.define(f.name(), MynType.FUNCTION, false, true);
                state.pushScope();
                for (int i = 0; i < f.params().size(); i++) {
                    String typeAnn = f.typeAnns().get(i);
                    MynType paramType = typeAnn == null ? MynType.ANY : MynType.fromAnnotation(typeAnn);
                    Token token = new Token(TokenType.IDENTIFIER, f.params().get(i), null, f.name().line(), f.name().col());
                    state.define(token, paramType, false, false);
                }

                MynType previousReturn = state.currentReturnType;
                state.currentReturnType = f.retType() == null ? MynType.UNIT : MynType.fromAnnotation(f.retType());
                for (Stmt child : f.body().statements()) {
                    checkStmt(child, state);
                }
                state.currentReturnType = previousReturn;
                state.popScope();
            }
            case Stmt.If(Expr cond, Stmt thenBranch, Stmt elseBranch) -> {
                require(cond, checkExpr(cond, state), MynType.BOOLEAN, "if condition");
                checkStmt(thenBranch, state);
                if (elseBranch != null) {
                    checkStmt(elseBranch, state);
                }
            }
            case Stmt.While(Expr cond, Stmt body) -> {
                require(cond, checkExpr(cond, state), MynType.BOOLEAN, "while condition");
                checkStmt(body, state);
            }
            case Stmt.Expr(Expr expr) -> checkExpr(expr, state);
            case Stmt.Return(Token keyword, Expr value) -> {
                if (state.currentReturnType == null) {
                    throw new CompileError(at(keyword) + " return is only allowed inside a function");
                }
                MynType actual = value == null ? MynType.UNIT : checkExpr(value, state);
                if (!assignable(state.currentReturnType, actual)) {
                    throw mismatch(keyword, "return value", state.currentReturnType, actual);
                }
            }
        }
    }

    private MynType checkExpr(Expr expr, TypeState state) {
        MynType result = switch (expr) {
            case Expr.Literal(Object value) -> literalType(value);
            case Expr.Grouping(Expr inner) -> checkExpr(inner, state);
            case Expr.Var(Token ignored) -> state.resolve(expr).type();
            case Expr.Assign(Token name, Expr value) -> {
                MynType rhs = checkExpr(value, state);
                Resolver.Symbol symbol = state.resolve(expr);
                if (!symbol.mutable()) {
                    throw new CompileError(at(name) + " cannot assign to immutable variable: " + name.lexeme());
                }
                if (symbol.type() != null && !assignable(symbol.type(), rhs)) {
                    throw mismatch(name, "variable '" + symbol.name() + "'", symbol.type(), rhs);
                }
                yield symbol.type();
            }
            case Expr.Unary(String op, Expr right) -> checkUnary(expr, op, right, state);
            case Expr.Binary(Expr left, String op, Expr right) -> checkBinary(expr, left, op, right, state);
            case Expr.Call(Expr callee, Token paren, List<Expr> args) -> checkCall(callee, paren, args, state);
            case Expr.Prefix(String op, Expr target) -> checkIncDec(expr, op, target, state);
            case Expr.Postfix(String op, Expr target) -> checkIncDec(expr, op, target, state);
        };
        state.exprTypes.put(expr, result);
        return result;
    }

    private MynType checkUnary(Expr expr, String op, Expr right, TypeState state) {
        MynType rightType = checkExpr(right, state);
        return switch (op) {
            case "-" -> {
                require(expr, rightType, MynType.INT, "unary '-'");
                yield MynType.INT;
            }
            case "!" -> {
                require(expr, rightType, MynType.BOOLEAN, "unary '!'");
                yield MynType.BOOLEAN;
            }
            default -> throw new CompileError("Unsupported unary operator: " + op);
        };
    }

    private MynType checkBinary(Expr expr, Expr left, String op, Expr right, TypeState state) {
        MynType leftType = checkExpr(left, state);
        MynType rightType = checkExpr(right, state);
        return switch (op) {
            case "+" -> {
                if (leftType == MynType.INT && rightType == MynType.INT) {
                    yield MynType.INT;
                }
                if (stringConcatCompatible(leftType) && stringConcatCompatible(rightType)) {
                    yield MynType.STRING;
                }
                throw mismatch(exprToken(left, right), "operator '+'", MynType.STRING, rightType);
            }
            case "-", "*", "/", "%" -> {
                require(expr, leftType, MynType.INT, "left operand of '" + op + "'");
                require(expr, rightType, MynType.INT, "right operand of '" + op + "'");
                yield MynType.INT;
            }
            case "<", "<=", ">", ">=" -> {
                require(expr, leftType, MynType.INT, "left operand of '" + op + "'");
                require(expr, rightType, MynType.INT, "right operand of '" + op + "'");
                yield MynType.BOOLEAN;
            }
            case "==", "!=" -> {
                if (!assignable(leftType, rightType) && !assignable(rightType, leftType)) {
                    throw new CompileError("Type mismatch for operator '" + op + "': " + leftType.displayName() + " vs " + rightType.displayName());
                }
                yield MynType.BOOLEAN;
            }
            case "&&", "||" -> {
                require(expr, leftType, MynType.BOOLEAN, "left operand of '" + op + "'");
                require(expr, rightType, MynType.BOOLEAN, "right operand of '" + op + "'");
                yield MynType.BOOLEAN;
            }
            default -> throw new CompileError("Unsupported binary operator: " + op);
        };
    }

    private MynType checkCall(Expr callee, Token paren, List<Expr> args, TypeState state) {
        if (callee instanceof Expr.Var(Token name)) {
            return switch (name.lexeme()) {
                case "print", "println" -> builtin(paren, args, state, 1, MynType.ANY, MynType.UNIT);
                case "typeOf", "toString" -> builtin(paren, args, state, 1, MynType.ANY, MynType.STRING);
                case "len" -> builtin(paren, args, state, 1, MynType.STRING, MynType.INT);
                default -> {
                    Resolver.Symbol symbol = state.resolve(callee);
                    if (symbol.function()) {
                        yield MynType.ANY;
                    }
                    throw new CompileError(at(paren) + " not a function");
                }
            };
        }
        throw new CompileError(at(paren) + " Only direct function calls are supported in the initial semantic pass");
    }

    private MynType builtin(Token token, List<Expr> args, TypeState state, int arity, MynType paramType, MynType returnType) {
        if (args.size() != arity) {
            throw new CompileError(at(token) + " arity mismatch: expected " + arity + " arguments but got " + args.size());
        }
        MynType actual = checkExpr(args.getFirst(), state);
        if (!assignable(paramType, actual)) {
            throw mismatch(token, "parameter 'value'", paramType, actual);
        }
        return returnType;
    }

    private MynType checkIncDec(Expr expr, String op, Expr target, TypeState state) {
        if (!(target instanceof Expr.Var(Token name))) {
            throw new CompileError("Invalid increment/decrement target");
        }
        Resolver.Symbol symbol = state.resolve(target);
        if (!symbol.mutable()) {
            throw new CompileError(at(name) + " cannot assign to immutable variable: " + name.lexeme());
        }
        if (symbol.type() != MynType.INT) {
            throw mismatch(name, "operator '" + op + "'", MynType.INT, symbol.type());
        }
        return MynType.INT;
    }

    record TypeCheckResult(Map<Expr, MynType> expressionTypes, Map<String, MynType> locals) {
    }

    private static final class TypeState {
        private final Resolver.Resolution resolution;
        private final Deque<Map<String, MynType>> scopes = new ArrayDeque<>();
        private final Map<Expr, MynType> exprTypes = new IdentityHashMap<>();
        private final Map<String, MynType> locals = new HashMap<>();
        private MynType currentReturnType;

        private TypeState(Resolver.Resolution resolution) {
            this.resolution = resolution;
            pushScope();
        }

        void pushScope() {
            scopes.push(new HashMap<>());
        }

        void popScope() {
            scopes.pop();
        }

        void define(Token token, MynType type, boolean mutable, boolean function) {
            scopes.peek().put(token.lexeme(), type == null ? (function ? MynType.FUNCTION : MynType.ANY) : type);
            locals.put(token.lexeme(), type == null ? (function ? MynType.FUNCTION : MynType.ANY) : type);
        }

        Resolver.Symbol resolve(Expr expr) {
            Resolver.Symbol symbol = resolution.localsByExpr().get(expr);
            if (symbol == null && expr instanceof Expr.Var(Token token)) {
                symbol = resolution.localsByName().get(token.lexeme());
            }
            if (symbol == null) {
                throw new CompileError("Unresolved expression: " + expr);
            }
            return symbol;
        }
    }

    private static MynType literalType(Object value) {
        if (value instanceof Double) return MynType.INT;
        if (value instanceof String) return MynType.STRING;
        if (value instanceof Boolean) return MynType.BOOLEAN;
        return MynType.UNIT;
    }

    private static boolean assignable(MynType expected, MynType actual) {
        return expected == MynType.ANY || expected == actual;
    }

    private static boolean stringConcatCompatible(MynType type) {
        return type == MynType.STRING || type == MynType.INT || type == MynType.BOOLEAN || type == MynType.UNIT;
    }

    private static void require(Expr expr, MynType actual, MynType expected, String context) {
        if (!assignable(expected, actual)) {
            throw mismatch(exprToken(expr, expr), context, expected, actual);
        }
    }

    private static CompileError mismatch(Token token, String context, MynType expected, MynType actual) {
        return new CompileError(at(token) + " Type mismatch for " + context + ": expected " + expected.displayName() + " but got " + actual.displayName());
    }

    private static Token exprToken(Expr left, Expr fallback) {
        if (left instanceof Expr.Var(Token token)) {
            return token;
        }
        if (left instanceof Expr.Assign(Token token, Expr ignored)) {
            return token;
        }
        if (left instanceof Expr.Call(Expr ignored, Token token, List<Expr> ignoredArgs)) {
            return token;
        }
        if (fallback instanceof Expr.Var(Token token)) {
            return token;
        }
        return new Token(TokenType.IDENTIFIER, "<expr>", null, 0, 0);
    }

    private static String at(Token token) {
        return "[line " + token.line() + ", col " + token.col() + "]";
    }
}
