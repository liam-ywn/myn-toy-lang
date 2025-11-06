package yewintnaing.dev.myn;

import java.util.*;

final class Interpreter {
    private Environment env = new Environment();
    private int functionDepth = 0;

    Interpreter() {
//        env.define("print", new Value.UnitV());
//        env.define("println", new Value.UnitV());
    }

    void exec(List<Stmt> stmts) {
        installBuiltins(env);
        for (Stmt s : stmts) exec(s);
    }

    private void installBuiltins(Environment base) {
        base.define("print", new Value.NativeV(1, args -> {
            System.out.print(stringify(args.getFirst()));
            return new Value.UnitV();
        }));
        base.define("println", new Value.NativeV(1, args -> {
            System.out.println(stringify(args.getFirst()));
            return new Value.UnitV();
        }));
    }


    private void exec(Stmt s) {
        if (s instanceof Stmt.Block(List<Stmt> statements)) {
            execBlock(statements, new Environment(env));
        } else if (s instanceof Stmt.Var v) {
            Value init = (v.init() == null) ? new Value.UnitV() : eval(v.init());

            if (env.isExists(v.name())) {
                throw new RuntimeException(v.name() + " already defined");
            }

            env.define(v.name(), init, v.mutable());
        } else if (s instanceof Stmt.Func f) {
            Value.FuncV fun = new Value.FuncV(
                    f.params(),
                    f.typeAnns(),
                    List.copyOf(f.body().statements()),
                    env /* closure */
            );
            env.define(f.name(), fun);
        } else if (s instanceof Stmt.If(Expr cond, Stmt thenBranch, Stmt elseBranch)) {
            if (truthy(eval(cond))) exec(thenBranch);
            else if (elseBranch != null) exec(elseBranch);
        } else if (s instanceof Stmt.While(Expr cond, Stmt body)) {
            while (truthy(eval(cond))) exec(body);
        } else if (s instanceof Stmt.Expr(Expr expr)) {
            eval(expr);
        } else if (s instanceof Stmt.Return(Expr value)) {

            if (functionDepth == 0) {
                throw new RuntimeException("return is only allowed inside a function");
            }

            throw new ReturnSignal(value == null ? new Value.UnitV() : eval(value));
        }
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

        if (e instanceof Expr.Var(String name)) {
            return env.get(name);
        }

        if (e instanceof Expr.Assign(String name, Expr value)) {
            Value val = eval(value);


            if (!env.assign(name, val)) {
                throw new RuntimeException("Undefined variable: " + name);
            }
            return val;
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

        if (e instanceof Expr.Call(Expr callee, List<Expr> args)) {
            Value calleeValue = eval(callee);
            List<Value> evalArgs = new ArrayList<>();
            for (Expr a : args) evalArgs.add(eval(a));
            return callFunction(calleeValue, evalArgs);
        }

        throw new RuntimeException("unhandled expr: " + e);
    }

    private Value callFunction(Value callee, List<Value> args) {
        if (callee instanceof Value.NativeV(int arity, java.util.function.Function<List<Value>, Value> call)) {
            if (args.size() != arity) throw new RuntimeException("arity mismatch");
            return call.apply(args);
        }
        if (callee instanceof Value.FuncV f) {
            Environment local = new Environment(f.closure());
            for (int i = 0; i < f.params().size(); i++) {
                String name = f.params().get(i);
                Value val = i < args.size() ? args.get(i) : new Value.UnitV();
                local.define(name, val);
            }
            try {
                functionDepth++;
                execBlock(f.body(), local);
            } catch (ReturnSignal rs) {
                return rs.value;
            }finally {
                functionDepth--;
            }
            return new Value.UnitV();
        }
        throw new RuntimeException("not a function");
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

    private static String stringify(Value v) {
        return switch (v) {
            case Value.IntV i -> Long.toString(i.v());
            case Value.BoolV b -> Boolean.toString(b.v());
            case Value.StrV s -> s.v();
            case Value.UnitV u -> "Unit";
            default -> v.toString();
        };
    }

    // used to unwind to the function call site
    private static final class ReturnSignal extends RuntimeException {
        final Value value;

        ReturnSignal(Value v) {
            this.value = v;
        }
    }
}