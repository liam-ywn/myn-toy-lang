package yewintnaing.dev.myn;

import java.nio.file.*;
import java.io.*;
import java.util.*;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            repl();
        } else {
            String src = Files.readString(Path.of(args[0]));
            run(src);
        }
    }

    // --------------------------------------------------------------------------
    // REPL (interactive shell)
    // --------------------------------------------------------------------------
    private static void repl() throws IOException {
        System.out.println("Myn v0.1 — Kotlin-like scripting language (Ctrl+D to exit)");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder buffer = new StringBuilder();
        Interpreter interpreter = new Interpreter();
        String line;

        while (true) {
            System.out.print("> ");
            line = reader.readLine();
            if (line == null) break; // EOF
            if (line.isBlank()) continue;
            buffer.append(line).append('\n');

            // interpret when user ends a statement
            if (isSubmissionReady(buffer.toString(), line)) {
                runReplChunk(buffer.toString(), interpreter, !endsWithStatementTerminator(line));
                buffer.setLength(0);
            }
        }
    }

    private static void run(String src) {
        run(src, new Interpreter());
    }

    private static void run(String src, Interpreter interpreter) {
        try {
            List<Stmt> program = parse(src);
            interpreter.exec(program);

        } catch (Exception e) {
            System.err.println("[Error] " + e.getMessage());
        }
    }

    private static void runReplChunk(String src, Interpreter interpreter, boolean printExpressionResult) {
        try {
            List<Stmt> program = parse(printExpressionResult ? src + ";" : src);
            Value result = interpreter.execForRepl(program);
            if (printExpressionResult && !(result instanceof Value.UnitV)) {
                System.out.println(Interpreter.stringify(result));
            }
        } catch (Exception e) {
            System.err.println("[Error] " + e.getMessage());
        }
    }

    private static List<Stmt> parse(String src) {
        Lexer lexer = new Lexer(src);
        List<Token> tokens = lexer.scan();
        Parser parser = new Parser(tokens);
        return parser.parse();
    }

    private static boolean isSubmissionReady(String buffer, String currentLine) {
        if (endsWithStatementTerminator(currentLine)) {
            return true;
        }

        try {
            parse(buffer + ";");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean endsWithStatementTerminator(String line) {
        String trimmed = line.trim();
        return trimmed.endsWith(";") || trimmed.endsWith("}");
    }
}
