package yewintnaing.dev.myn;

record Token(TokenType type, String lexeme, Object literal, int line, int col) {
    @Override
    public String toString() {
        return type + " " + lexeme + " " + literal;
    }
}