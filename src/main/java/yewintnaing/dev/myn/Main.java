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
        String line;

        while (true) {
            System.out.print("> ");
            line = reader.readLine();
            if (line == null) break; // EOF
            buffer.append(line).append('\n');

            // interpret when user ends a statement
            if (line.trim().endsWith(";") || line.trim().endsWith("}")) {
                run(buffer.toString());
                buffer.setLength(0);
            }
        }
    }

    private static void run(String src) {
        try {
            // Lexical analysis
            Lexer lexer = new Lexer(src);
            List<Token> tokens = lexer.scan();

            // Parsing into AST
            Parser parser = new Parser(tokens);
            List<Stmt> program = parser.parse();

            // Interpretation
            Interpreter interpreter = new Interpreter();
//            System.out.println(program.size());
            interpreter.exec(program);

        } catch (Exception e) {
            System.err.println("[Error] " + e.getMessage());
        }
    }
}