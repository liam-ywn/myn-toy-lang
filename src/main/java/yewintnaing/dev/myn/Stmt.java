package yewintnaing.dev.myn;

import java.util.List;

sealed interface Stmt permits Stmt.Block, Stmt.Var, Stmt.Func, Stmt.If, Stmt.While, Stmt.Expr, Stmt.Return {
    record Block(List<Stmt> statements) implements Stmt {
    }

    record Var(boolean mutable, String name, yewintnaing.dev.myn.Expr init, String typeAnn) implements Stmt {
    }

    record Func(String name, List<String> params, List<String> typeAnns, String retType,
                Stmt.Block body) implements Stmt {
    }

    record If(yewintnaing.dev.myn.Expr cond, Stmt thenBranch, Stmt elseBranch) implements Stmt {
    }

    record While(yewintnaing.dev.myn.Expr cond, Stmt body) implements Stmt {
    }

    record Expr(yewintnaing.dev.myn.Expr expr) implements Stmt {
    }

    record Return(yewintnaing.dev.myn.Expr value) implements Stmt {
    }
}

