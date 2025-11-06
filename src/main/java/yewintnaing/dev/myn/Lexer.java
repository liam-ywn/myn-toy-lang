package yewintnaing.dev.myn;

import java.util.*;

final class Lexer {
    private final String src;
    private final List<Token> tokens = new ArrayList<>();
    private int start = 0, current = 0, line = 1, col = 1;
    private static final Map<String, TokenType> KW = Map.ofEntries(
            Map.entry("fun", TokenType.FUN), Map.entry("let", TokenType.LET), Map.entry("var", TokenType.VAR),
            Map.entry("if", TokenType.IF), Map.entry("else", TokenType.ELSE), Map.entry("while", TokenType.WHILE),
            Map.entry("return", TokenType.RETURN), Map.entry("true", TokenType.TRUE), Map.entry("false", TokenType.FALSE)
    );

    Lexer(String src) {
        this.src = src;
    }

    List<Token> scan() {
        while (!isAtEnd()) {
            start = current;
            scanToken();
        }
        tokens.add(new Token(TokenType.EOF, "", null, line, col));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case '(' -> add(TokenType.LEFT_PAREN);
            case ')' -> add(TokenType.RIGHT_PAREN);
            case '{' -> add(TokenType.LEFT_BRACE);
            case '}' -> add(TokenType.RIGHT_BRACE);
            case ',' -> add(TokenType.COMMA);
            case '.' -> add(TokenType.DOT);
            case ';' -> add(TokenType.SEMICOLON);
            case ':' -> add(TokenType.COLON);
            case '+' -> add(TokenType.PLUS);
            case '-' -> add(TokenType.MINUS);
            case '*' -> add(TokenType.STAR);
            case '/' -> {
                if (match('/')) {
                    while (peek() != '\n' && !isAtEnd()) advance();
                } else add(TokenType.SLASH);
            }
            case '%' -> add(TokenType.PERCENT);
            case '!' -> add(match('=') ? TokenType.BANG_EQUAL : TokenType.BANG);
            case '=' -> add(match('=') ? TokenType.EQUAL_EQUAL : TokenType.EQUAL);
            case '<' -> add(match('=') ? TokenType.LESS_EQUAL : TokenType.LESS);
            case '>' -> add(match('=') ? TokenType.GREATER_EQUAL : TokenType.GREATER);
            case '&' -> {
                if (match('&')) add(TokenType.AND_AND);
                else err("Unexpected '&'");
            }
            case '|' -> {
                if (match('|')) add(TokenType.OR_OR);
                else err("Unexpected '|'");
            }
            case ' ', '\r', '\t' -> {
            }
            case '\n' -> {
                line++;
                col = 0;
            }
            case '"' -> string();
            default -> {
                if (isDigit(c)) number();
                else if (isAlpha(c)) identifier();
                else err("Unexpected character: '" + c + "'");
            }
        }
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) advance();
        String text = src.substring(start, current);
        TokenType type = KW.getOrDefault(text, TokenType.IDENTIFIER);
        add(type);
    }

    private void number() {
        while (isDigit(peek())) advance();
        if (peek() == '.' && isDigit(peekNext())) {
            do advance();
            while (isDigit(peek()));
        }
        add(TokenType.NUMBER, Double.parseDouble(src.substring(start, current)));
    }

    private void string() {
        while (!isAtEnd() && peek() != '"') {
            if (peek() == '\n') {
                line++;
                col = 0;
            }
            advance();
        }
        if (isAtEnd()) err("Unterminated string");
        advance(); // closing quote
        String val = src.substring(start + 1, current - 1);
        add(TokenType.STRING, val);
    }

    private boolean match(char expected) {
        if (isAtEnd() || src.charAt(current) != expected) return false;
        current++;
        col++;
        return true;
    }

    private char advance() {
        char c = src.charAt(current++);
        col++;
        return c;
    }

    private char peek() {
        return isAtEnd() ? '\0' : src.charAt(current);
    }

    private char peekNext() {
        return (current + 1 >= src.length()) ? '\0' : src.charAt(current + 1);
    }

    private boolean isAtEnd() {
        return current >= src.length();
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isAlpha(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private void add(TokenType t) {
        add(t, null);
    }

    private void add(TokenType t, Object lit) {
        tokens.add(new Token(t, src.substring(start, current), lit, line, col));
    }

    private static void err(String msg) {
        throw new RuntimeException(msg);
    }
}