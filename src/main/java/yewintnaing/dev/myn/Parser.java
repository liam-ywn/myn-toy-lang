package yewintnaing.dev.myn;

import java.util.*;

import static yewintnaing.dev.myn.TokenType.*;

final class Parser {
    private final List<Token> t;
    private int i = 0;

    Parser(List<Token> tokens) {
        this.t = tokens;
    }

    List<Stmt> parse() {
        List<Stmt> res = new ArrayList<>();
        while (!isAtEnd()) {
            res.add(declaration());
        }
        return res;
    }


    private Stmt declaration() {
        if (match(TokenType.FUN)) return funDecl();
        if (match(TokenType.LET, TokenType.VAR)) return varDecl(prev().type() == TokenType.VAR);
        return statement();
    }

    private Stmt funDecl() {
        Token name = consume(TokenType.IDENTIFIER, "Expected function name");
        consume(TokenType.LEFT_PAREN, "(");

        List<String> params = new ArrayList<>();
        List<String> anns = new ArrayList<>();

        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                params.add(consume(TokenType.IDENTIFIER, "param name").lexeme());
                if (match(TokenType.COLON)) {
                    anns.add(consume(TokenType.IDENTIFIER, "type").lexeme());
                } else {
                    anns.add(null);
                }
            } while (match(TokenType.COMMA));
        }

        consume(TokenType.RIGHT_PAREN, ")");
        String retType = null;
        if (match(TokenType.COLON)) retType = consume(TokenType.IDENTIFIER, "return type").lexeme();

        Stmt.Block body = block();
        return new Stmt.Func(name, params, anns, retType, body);
    }

    private Stmt varDecl(boolean mutable) {
        Token name = consume(TokenType.IDENTIFIER, "var name");
        String type = null;
        if (match(TokenType.COLON)) type = consume(TokenType.IDENTIFIER, "type").lexeme();
        Expr init = null;
        if (match(TokenType.EQUAL)) init = expression();
        consume(TokenType.SEMICOLON, ";");
        return new Stmt.Var(mutable, name, init, type);
    }

    // --- Statements ---

    private Stmt statement() {
        if (match(TokenType.IF)) return ifStmt();
        if (match(TokenType.WHILE)) return whileStmt();
        if (match(TokenType.RETURN)) return returnStmt();
        if (match(TokenType.LEFT_BRACE)) {
            // we've consumed '{' in match; put it back for block()
            i--;
            return block();
        }
        return new Stmt.Expr(expressionThenSemicolon());
    }

    private Stmt ifStmt() {
        consume(TokenType.LEFT_PAREN, "(");
        Expr cond = expression();
        consume(TokenType.RIGHT_PAREN, ")");

        Stmt thenB = statement();
        Stmt elseB = match(TokenType.ELSE) ? statement() : null;
        return new Stmt.If(cond, thenB, elseB);
    }

    private Stmt whileStmt() {
        consume(TokenType.LEFT_PAREN, "(");
        Expr cond = expression();
        consume(TokenType.RIGHT_PAREN, ")");
        Stmt body = statement();
        return new Stmt.While(cond, body);
    }

    private Stmt.Return returnStmt() {
        Token keyword = prev();
        Expr v = null;
        if (!check(TokenType.SEMICOLON)) v = expression();
        consume(TokenType.SEMICOLON, ";");
        return new Stmt.Return(keyword, v);
    }

    private Stmt.Block block() {
        consume(TokenType.LEFT_BRACE, "{");
        List<Stmt> list = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) list.add(declaration());
        consume(TokenType.RIGHT_BRACE, "}");
        return new Stmt.Block(list);
    }

    // --- Expressions ---

    private Expr expressionThenSemicolon() {
        Expr e = expression();
        consume(TokenType.SEMICOLON, ";");
        return e;
    }

    private Expr expression() {
        return assignment();
    }

    private Expr assignment() {
        Expr expr = logicOr();
        if (match(TokenType.EQUAL)) {
            Token equals = prev();
            if (expr instanceof Expr.Var(Token name)) {
                return new Expr.Assign(name, assignment());
            }
            throw error(equals, "Invalid assignment target");
        }
        return expr;
    }

    private Expr logicOr() {
        Expr expr = logicAnd();
        while (match(TokenType.OR_OR)) {
            expr = new Expr.Binary(expr, "||", logicAnd());
        }
        return expr;
    }

    private Expr logicAnd() {
        Expr expr = equality();
        while (match(TokenType.AND_AND)) {
            expr = new Expr.Binary(expr, "&&", equality());
        }
        return expr;
    }

    private Expr equality() {
        Expr expr = comparison();
        while (match(TokenType.BANG_EQUAL, TokenType.EQUAL_EQUAL)) {
            String op = prev().lexeme();
            expr = new Expr.Binary(expr, op, comparison());
        }
        return expr;
    }

    private Expr comparison() {
        Expr expr = term();
        while (match(TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL)) {
            String op = prev().lexeme();
            expr = new Expr.Binary(expr, op, term());
        }
        return expr;
    }

    private Expr term() {
        Expr expr = factor();
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            String op = prev().lexeme();
            expr = new Expr.Binary(expr, op, factor());
        }
        return expr;
    }

    private Expr factor() {
        Expr expr = unary();
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            String op = prev().lexeme();
            expr = new Expr.Binary(expr, op, unary());
        }
        return expr;
    }

//    private Expr unary() {
//        if (match(TokenType.BANG, TokenType.MINUS)) {
//            return new Expr.Unary(prev().lexeme(), unary());
//        }
//        return call();
//    }

    private Expr unary() {
        if (match(BANG, MINUS)) {
            return new Expr.Unary(prev().lexeme(), unary());
        }
        if (match(PLUS_PLUS, MINUS_MINUS)) {
            Token op = prev();
            Expr target = call();
            ensureVariableTarget(target, op, "prefix");
            return new Expr.Prefix(op.lexeme(), target);
        }
        return postfix();
    }

    private Expr postfix() {
        Expr expr = call();
        while (match(PLUS_PLUS, MINUS_MINUS)) {
            Token op = prev();
            ensureVariableTarget(expr, op, "postfix");
            expr = new Expr.Postfix(op.lexeme(), expr);
        }
        return expr;
    }

    private Expr call() {
        Expr expr = primary();
        while (match(TokenType.LEFT_PAREN)) {
            Token paren = prev();
            List<Expr> args = new ArrayList<>();
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    args.add(expression());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RIGHT_PAREN, ")");
            expr = new Expr.Call(expr, paren, args);
        }
        return expr;
    }

    private Expr primary() {
        if (match(TokenType.FALSE)) return new Expr.Literal(false);
        if (match(TokenType.TRUE)) return new Expr.Literal(true);
        if (match(TokenType.NUMBER)) return new Expr.Literal(prev().literal());
        if (match(TokenType.STRING)) return new Expr.Literal(prev().literal());
        if (match(TokenType.IDENTIFIER)) return new Expr.Var(prev());
        if (match(TokenType.LEFT_PAREN)) {
            Expr e = expression();
            consume(TokenType.RIGHT_PAREN, ")");
            return new Expr.Grouping(e);
        }
        throw error(peek(), "UnExpected token");
    }

    private boolean match(TokenType... types) {
        for (var tp : types) {
            if (check(tp)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private boolean check(TokenType type) {
        TokenType currentType = peek().type();
        return !isAtEnd() && currentType == type;
    }

    private Token advance() {
        if (!isAtEnd()) i++;
        return prev();
    }

    private boolean isAtEnd() {
        return peek().type() == TokenType.EOF;
    }

    private Token peek() {
        return t.get(i);
    }

    private Token prev() {
        return t.get(i - 1);
    }

    private Token consume(TokenType type, String msg) {
        if (check(type)) return advance();
        throw error(peek(), msg + " at " + peek().line() + ":" + peek().col());
    }


    private static RuntimeException error(Token tok, String msg) {
        return new RuntimeException("[line" + tok.line() + ", col " + tok.col() + "] " + msg + " " + tok.lexeme());
    }

    private static void ensureVariableTarget(Expr expr, Token op, String position) {
        if (!(expr instanceof Expr.Var)) {
            throw error(op, "Invalid " + position + " increment/decrement target");
        }
    }
}
