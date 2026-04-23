package yewintnaing.dev.myn;

import java.util.*;

final class Interpreter {
    private Environment env = new Environment();
    private int functionDepth = 0;

    Interpreter() {
        installBuiltins(env);
    }

    void exec(List<Stmt> stmts) {
        for (Stmt s : stmts) exec(s);
    }

    Value execForRepl(List<Stmt> stmts) {
        Value last = new Value.UnitV();
        for (Stmt s : stmts) {
            last = execForRepl(s);
        }
        return last;
    }

    private void installBuiltins(Environment base) {
        base.define("print", new Value.NativeV(List.of("value"), List.of("Any"), "Unit", args -> {
            System.out.print(stringify(args.getFirst()));
            return new Value.UnitV();
        }));
        base.define("println", new Value.NativeV(List.of("value"), List.of("Any"), "Unit", args -> {
            System.out.println(stringify(args.getFirst()));
            return new Value.UnitV();
        }));
        base.define("typeOf", new Value.NativeV(List.of("value"), List.of("Any"), "String", args ->
                new Value.StrV(typeName(args.getFirst()))
        ));
        base.define("toString", new Value.NativeV(List.of("value"), List.of("Any"), "String", args ->
                new Value.StrV(stringify(args.getFirst()))
        ));
        base.define("len", new Value.NativeV(List.of("value"), List.of("String"), "Int", args ->
                new Value.IntV(args.getFirst() instanceof Value.StrV(String v) ? v.length() : 0)
        ));
    }


    private void exec(Stmt s) {
        if (s instanceof Stmt.Block(List<Stmt> statements)) {
            execBlock(statements, new Environment(env));
        } else if (s instanceof Stmt.Var v) {
            Value init = (v.init() == null) ? new Value.UnitV() : eval(v.init());
            assertMatchesType(v.typeAnn(), init, "variable '" + v.name().lexeme() + "'", v.name());

            if (env.isExists(v.name().lexeme())) {
                throw new RuntimeException(v.name().lexeme() + " already defined");
            }

            env.define(v.name().lexeme(), init, v.mutable(), v.typeAnn());
        } else if (s instanceof Stmt.Func f) {
            Value.FuncV fun = new Value.FuncV(
                    f.params(),
                    f.typeAnns(),
                    f.retType(),
                    List.copyOf(f.body().statements()),
                    env /* closure */
            );
            env.define(f.name().lexeme(), fun);
        } else if (s instanceof Stmt.If(Expr cond, Stmt thenBranch, Stmt elseBranch)) {
            if (truthy(eval(cond))) exec(thenBranch);
            else if (elseBranch != null) exec(elseBranch);
        } else if (s instanceof Stmt.While(Expr cond, Stmt body)) {
            while (truthy(eval(cond))) exec(body);
        } else if (s instanceof Stmt.Expr(Expr expr)) {
            eval(expr);
        }
        else if (s instanceof Stmt.Return(Token keyword, Expr value)) {

            if (functionDepth == 0) {
                throw new RuntimeException("return is only allowed inside a function");
            }

            throw new ReturnSignal(value == null ? new Value.UnitV() : eval(value), keyword);
        }
    }

    private Value execForRepl(Stmt s) {
        if (s instanceof Stmt.Expr(Expr expr)) {
            // REPL expression statements evaluate to a visible result, while
            // non-expression statements keep normal statement semantics.
            return eval(expr);
        }
        exec(s);
        return new Value.UnitV();
    }

    private void execBlock(List<Stmt> statements, Environment local) {
        Environment prev = env;
        try {
            env = local;
            for (Stmt s : statements) exec(s);
        } finally {
            env = prev;
        }
    }


    private Value eval(Expr e) {
        if (e instanceof Expr.Literal(Object value)) {


            if (value instanceof Double d) {
                return new Value.IntV(d.longValue());
            }
            if (value instanceof String s) return new Value.StrV(s);
            if (value instanceof Boolean b) return new Value.BoolV(b);
            return new Value.UnitV();
        }

        if (e instanceof Expr.Var(Token name)) {
            return env.get(name.lexeme());
        }

        if (e instanceof Expr.Assign(Token name, Expr value)) {
            Value val = eval(value);
            Environment.Slot slot = resolveSlot(name.lexeme());
            // A typed binding keeps its declared type for every future assignment.
            assertMatchesType(slot.typeAnn(), val, "variable '" + name.lexeme() + "'", name);


            if (!env.assign(name.lexeme(), val)) {
                throw new RuntimeException("Undefined variable: " + name.lexeme());
            }
            return val;
        }

        if (e instanceof Expr.Postfix(String op, Expr target)) {
            Value val = eval(target);
            return switch (op) {
                case "++" -> {
                    if (val instanceof Value.IntV(long v)) {
                        long newVal = v + 1;
                        env.assign(((Expr.Var) target).name().lexeme(), new Value.IntV(newVal));
                        yield new Value.IntV(v);
                    }else {
                        throw new RuntimeException("Postfix ++ can only be applied to Int");
                    }
                }
                case "--" -> {
                    if (val instanceof Value.IntV(long v)) {
                        long newVal = v - 1;
                        env.assign(((Expr.Var) target).name().lexeme(), new Value.IntV(newVal));
                        yield new Value.IntV(v);
                    }else {
                        throw new RuntimeException("Postfix -- can only be applied to Int");
                    }
                }
                default -> throw new RuntimeException("bad postfix op: " + op);
            };
        }

        if (e instanceof Expr.Prefix(String op, Expr target)) {
            Value val = eval(target);
            return switch (op) {
                case "++" -> {
                    if (val instanceof Value.IntV(long v)) {
                        long newVal = v + 1;
                        env.assign(((Expr.Var) target).name().lexeme(), new Value.IntV(newVal));
                        yield new Value.IntV(newVal);
                    } else {
                        throw new RuntimeException("Prefix ++ can only be applied to Int");
                    }
                }
                case "--" -> {
                    if (val instanceof Value.IntV(long v)) {
                        long newVal = v - 1;
                        env.assign(((Expr.Var) target).name().lexeme(), new Value.IntV(newVal));
                        yield new Value.IntV(newVal);
                    } else {
                        throw new RuntimeException("Prefix -- can only be applied to Int");
                    }
                }
                default -> throw new RuntimeException("bad prefix op: " + op);
            };
        }

        if (e instanceof Expr.Unary(String op, Expr right)) {
            Value r = eval(right);
            return switch (op) {
                case "-" -> new Value.IntV(-asInt(r));
                case "!" -> new Value.BoolV(!truthy(r));
                default -> throw new RuntimeException("bad unary op: " + op);
            };
        }

        if (e instanceof Expr.Binary(Expr left, String op, Expr right)) {
            Value l = eval(left);
            Value r = eval(right);
            return switch (op) {
                case "+" -> add(l, r);
                case "-" -> new Value.IntV(asInt(l) - asInt(r));
                case "*" -> new Value.IntV(asInt(l) * asInt(r));
                case "/" -> new Value.IntV(asInt(l) / asInt(r));
                case "%" -> new Value.IntV(asInt(l) % asInt(r));
                case "==" -> new Value.BoolV(equalsV(l, r));
                case "!=" -> new Value.BoolV(!equalsV(l, r));
                case "<" -> new Value.BoolV(asInt(l) < asInt(r));
                case "<=" -> new Value.BoolV(asInt(l) <= asInt(r));
                case ">" -> new Value.BoolV(asInt(l) > asInt(r));
                case ">=" -> new Value.BoolV(asInt(l) >= asInt(r));
                case "&&" -> new Value.BoolV(truthy(l) && truthy(r));
                case "||" -> new Value.BoolV(truthy(l) || truthy(r));
                default -> throw new RuntimeException("bad op: " + op);
            };
        }

        if (e instanceof Expr.Grouping(Expr expr)) {
            return eval(expr);
        }

        if (e instanceof Expr.Call(Expr callee, Token paren, List<Expr> args)) {
            Value calleeValue = eval(callee);
            List<Value> evalArgs = new ArrayList<>();
            for (Expr a : args) evalArgs.add(eval(a));
            return callFunction(calleeValue, evalArgs, paren);
        }

        throw new RuntimeException("unhandled expr: " + e);
    }

    private Value callFunction(Value callee, List<Value> args, Token callSite) {
        if (callee instanceof Value.NativeV(var params, var typeAnns, var retType,
                java.util.function.Function<List<Value>, Value> call)) {
            if (args.size() != params.size()) {
                throw new RuntimeException(at(callSite) + " arity mismatch: expected " + params.size() + " arguments but got " + args.size());
            }
            for (int i = 0; i < params.size(); i++) {
                assertMatchesType(typeAnns.get(i), args.get(i), "parameter '" + params.get(i) + "'", callSite);
            }
            Value result = call.apply(args);
            assertMatchesType(retType, result, "return value", callSite);
            return result;
        }
        if (callee instanceof Value.FuncV f) {
            if (args.size() != f.params().size()) {
                throw new RuntimeException(at(callSite) + " arity mismatch: expected " + f.params().size() + " arguments but got " + args.size());
            }
            // User functions execute in a fresh local scope chained to the
            // closure they captured when the function was declared.
            Environment local = new Environment(f.closure());
            for (int i = 0; i < f.params().size(); i++) {
                String name = f.params().get(i);
                Value val = args.get(i);
                String typeAnn = f.typeAnns().get(i);
                assertMatchesType(typeAnn, val, "parameter '" + name + "'", callSite);
                local.define(name, val, false, typeAnn);
            }
            try {
                functionDepth++;
                execBlock(f.body(), local);
            } catch (ReturnSignal rs) {
                assertMatchesType(f.retType(), rs.value, "return value", rs.keyword);
                return rs.value;
            }finally {
                functionDepth--;
            }
            assertMatchesType(f.retType(), new Value.UnitV(), "return value", callSite);
            return new Value.UnitV();
        }
        throw new RuntimeException(at(callSite) + " not a function");
    }

    // --- Helpers ---------------------------------------------------------------

    private static Value add(Value l, Value r) {
        if (l instanceof Value.StrV(String v)) return new Value.StrV(v + stringify(r));
        if (r instanceof Value.StrV(String v)) return new Value.StrV(stringify(l) + v);
        return new Value.IntV(asInt(l) + asInt(r));
    }

    private static long asInt(Value v) {
        if (v instanceof Value.IntV(long v1)) return v1;
        throw new RuntimeException("expected Int");
    }

    private static boolean truthy(Value v) {
        return switch (v) {
            case Value.BoolV b -> b.v();
            case Value.UnitV u -> false;
            case Value.IntV i -> i.v() != 0;
            case Value.StrV s -> !s.v().isEmpty();
            default -> true; // functions, etc. are truthy
        };
    }

    private static boolean equalsV(Value a, Value b) {
        return switch (a) {
            case Value.IntV ai -> (b instanceof Value.IntV(long v)) && ai.v() == v;
            case Value.BoolV ab -> (b instanceof Value.BoolV(boolean v)) && ab.v() == v;
            case Value.StrV as -> (b instanceof Value.StrV(String v)) && as.v().equals(v);
            case Value.UnitV u -> b instanceof Value.UnitV;
            default -> a == b;
        };
    }

    static String stringify(Value v) {
        return switch (v) {
            case Value.IntV i -> Long.toString(i.v());
            case Value.BoolV b -> Boolean.toString(b.v());
            case Value.StrV s -> s.v();
            case Value.UnitV u -> "Unit";
            default -> v.toString();
        };
    }

    private Environment.Slot resolveSlot(String name) {
        Environment current = env;
        while (current != null) {
            Environment.Slot slot = current.getSlot(name);
            if (slot != null) {
                return slot;
            }
            current = current.parent();
        }
        throw new RuntimeException("Undefined variable: " + name);
    }

    private static void assertMatchesType(String typeAnn, Value value, String context, Token token) {
        if (typeAnn == null) {
            return;
        }

        // Type annotations are enforced at runtime right now, so we attach the
        // source token to each failure to keep the error actionable.
        if (!matchesType(typeAnn, value)) {
            throw new RuntimeException(at(token) + " Type mismatch for " + context + ": expected " + typeAnn + " but got " + typeName(value));
        }
    }

    private static boolean matchesType(String typeAnn, Value value) {
        return switch (typeAnn) {
            case "Any" -> true;
            case "Int" -> value instanceof Value.IntV;
            case "String" -> value instanceof Value.StrV;
            case "Boolean" -> value instanceof Value.BoolV;
            case "Unit" -> value instanceof Value.UnitV;
            default -> throw new RuntimeException("Unknown type annotation: " + typeAnn);
        };
    }

    private static String typeName(Value value) {
        return switch (value) {
            case Value.IntV ignored -> "Int";
            case Value.StrV ignored -> "String";
            case Value.BoolV ignored -> "Boolean";
            case Value.UnitV ignored -> "Unit";
            case Value.FuncV ignored -> "Function";
            case Value.NativeV ignored -> "Function";
        };
    }

    private static String at(Token token) {
        return "[line " + token.line() + ", col " + token.col() + "]";
    }

    // used to unwind to the function call site
    private static final class ReturnSignal extends RuntimeException {
        final Value value;
        final Token keyword;

        ReturnSignal(Value v, Token keyword) {
            this.value = v;
            this.keyword = keyword;
        }
    }
}
