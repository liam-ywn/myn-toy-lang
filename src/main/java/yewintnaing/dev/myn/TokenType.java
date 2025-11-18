package yewintnaing.dev.myn;

enum TokenType {
    // Single-char
    LEFT_PAREN, RIGHT_PAREN, LEFT_BRACE, RIGHT_BRACE,
    COMMA, DOT, SEMICOLON, COLON,
    PLUS, MINUS, PLUS_PLUS, MINUS_MINUS, STAR, SLASH, PERCENT,
    BANG, EQUAL, LESS, GREATER,

    // Two-char
    BANG_EQUAL, EQUAL_EQUAL,
    LESS_EQUAL, GREATER_EQUAL,
    AND_AND, OR_OR,

    // Literals & id
    IDENTIFIER, STRING, NUMBER,

    // Keywords
    FUN, LET, VAR, IF, ELSE, WHILE, RETURN, TRUE, FALSE,

    EOF
}