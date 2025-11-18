package yewintnaing.dev.myn;

import java.util.List;

sealed interface Expr
    permits Expr.Assign,
            Expr.Binary,
            Expr.Call,
            Expr.Grouping,
            Expr.Literal,
            Expr.Postfix,
            Expr.Prefix,
            Expr.Unary,
            Expr.Var {

    record Literal(Object value) implements Expr {
    }

    record Var(String name) implements Expr {
    }

    record Assign(String name, Expr value) implements Expr {
    }

    record Unary(String op, Expr right) implements Expr {
    }

    record Binary(Expr left, String op, Expr right) implements Expr {
    }

    record Call(Expr callee, List<Expr> args) implements Expr {
    }

    record Grouping(Expr expr) implements Expr {
    }

    record Prefix(String op, Expr target) implements Expr { }

    record Postfix(String op, Expr target) implements Expr { }

}