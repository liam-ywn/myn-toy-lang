package yewintnaing.dev.myn;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class Resolver {
    Resolution resolve(List<Stmt> program) {
        ResolverState state = new ResolverState();
        for (Stmt stmt : program) {
            resolveStmt(stmt, state);
        }
        return new Resolution(state.localsByExpr, state.localsByName);
    }

    private void resolveStmt(Stmt stmt, ResolverState state) {
        switch (stmt) {
            case Stmt.Block(List<Stmt> statements) -> {
                state.pushScope();
                for (Stmt child : statements) {
                    resolveStmt(child, state);
                }
                state.popScope();
            }
            case Stmt.Var v -> {
                resolveExpr(v.init(), state);
                state.define(v.name(), v.typeAnn(), v.mutable(), false);
            }
            case Stmt.Func f -> {
                state.define(f.name(), "Function", false, true);
                state.pushScope();
                for (int i = 0; i < f.params().size(); i++) {
                    String typeAnn = f.typeAnns().get(i);
                    Token syntheticToken = new Token(TokenType.IDENTIFIER, f.params().get(i), null,
                            f.name().line(), f.name().col());
                    state.define(syntheticToken, typeAnn, false, false);
                }
                for (Stmt child : f.body().statements()) {
                    resolveStmt(child, state);
                }
                state.popScope();
            }
            case Stmt.If(Expr cond, Stmt thenBranch, Stmt elseBranch) -> {
                resolveExpr(cond, state);
                resolveStmt(thenBranch, state);
                if (elseBranch != null) {
                    resolveStmt(elseBranch, state);
                }
            }
            case Stmt.While(Expr cond, Stmt body) -> {
                resolveExpr(cond, state);
                resolveStmt(body, state);
            }
            case Stmt.Expr(Expr expr) -> resolveExpr(expr, state);
            case Stmt.Return(Token ignored, Expr value) -> resolveExpr(value, state);
        }
    }

    private void resolveExpr(Expr expr, ResolverState state) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case Expr.Literal ignored -> {
            }
            case Expr.Grouping(Expr inner) -> resolveExpr(inner, state);
            case Expr.Var(Token name) -> state.resolveUse(expr, name);
            case Expr.Assign(Token name, Expr value) -> {
                resolveExpr(value, state);
                state.resolveUse(expr, name);
            }
            case Expr.Unary(String ignored, Expr right) -> resolveExpr(right, state);
            case Expr.Binary(Expr left, String ignored, Expr right) -> {
                resolveExpr(left, state);
                resolveExpr(right, state);
            }
            case Expr.Call(Expr callee, Token ignored, List<Expr> args) -> {
                resolveExpr(callee, state);
                for (Expr arg : args) {
                    resolveExpr(arg, state);
                }
            }
            case Expr.Prefix(String ignored, Expr target) -> resolveExpr(target, state);
            case Expr.Postfix(String ignored, Expr target) -> resolveExpr(target, state);
        }
    }

    record Resolution(Map<Expr, Symbol> localsByExpr, Map<String, Symbol> localsByName) {
    }

    record Symbol(String name, MynType type, boolean mutable, boolean function, int scopeDepth, Token token) {
        String displayType() {
            return type == null ? "Uninferred" : type.displayName();
        }
    }

    private static final class ResolverState {
        private final Deque<Map<String, Symbol>> scopes = new ArrayDeque<>();
        private final Map<Expr, Symbol> localsByExpr = new IdentityHashMap<>();
        private final Map<String, Symbol> localsByName = new HashMap<>();

        private ResolverState() {
            pushScope();
            installBuiltins();
        }

        void pushScope() {
            scopes.push(new HashMap<>());
        }

        void popScope() {
            scopes.pop();
        }

        void define(Token token, String typeAnn, boolean mutable, boolean function) {
            Map<String, Symbol> scope = scopes.peek();
            if (scope.containsKey(token.lexeme())) {
                throw new CompileError(at(token) + " Duplicate definition: " + token.lexeme());
            }

            Symbol symbol = new Symbol(token.lexeme(), normalize(typeAnn, function), mutable, function, scopes.size() - 1, token);
            scope.put(token.lexeme(), symbol);
            localsByName.put(token.lexeme(), symbol);
        }

        void resolveUse(Expr expr, Token token) {
            for (Map<String, Symbol> scope : scopes) {
                Symbol symbol = scope.get(token.lexeme());
                if (symbol != null) {
                    localsByExpr.put(expr, symbol);
                    return;
                }
            }
            throw new CompileError(at(token) + " Undefined variable: " + token.lexeme());
        }

        private void installBuiltins() {
            defineBuiltin("print", MynType.FUNCTION);
            defineBuiltin("println", MynType.FUNCTION);
            defineBuiltin("typeOf", MynType.FUNCTION);
            defineBuiltin("toString", MynType.FUNCTION);
            defineBuiltin("len", MynType.FUNCTION);
        }

        private void defineBuiltin(String name, MynType type) {
            Token token = new Token(TokenType.IDENTIFIER, name, null, 0, 0);
            Symbol symbol = new Symbol(name, type, false, true, 0, token);
            scopes.peek().put(name, symbol);
            localsByName.put(name, symbol);
        }

        private static MynType normalize(String typeAnn, boolean function) {
            if (function) {
                return MynType.FUNCTION;
            }
            return MynType.fromAnnotation(typeAnn);
        }
    }

    private static String at(Token token) {
        return "[line " + token.line() + ", col " + token.col() + "]";
    }
}
