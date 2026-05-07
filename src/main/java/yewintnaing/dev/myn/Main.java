package yewintnaing.dev.myn;

import java.nio.file.*;
import java.io.*;
import java.util.*;

public class Main {
    private static final String COMPILE_FLAG = "--compile";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            repl();
        } else if (COMPILE_FLAG.equals(args[0])) {
            compileCommand(args);
        } else {
            String src = Files.readString(Path.of(args[0]));
            run(src);
        }
    }

    private static void compileCommand(String[] args) throws IOException {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Usage: --compile <source.myn> <output-dir> [ClassName]");
            return;
        }

        Path sourcePath = Path.of(args[1]);
        Path outputDir = Path.of(args[2]);
        String className = args.length == 4 ? args[3] : defaultClassName(sourcePath);

        try {
            String src = Files.readString(sourcePath);
            List<Stmt> program = parse(src);
            Resolver.Resolution resolution = new Resolver().resolve(program);
            TypeChecker.TypeCheckResult types = new TypeChecker().check(program, resolution);
            MynIr.Module ir = new IrLowerer().lower(program, types);
            new JvmIrCompiler().compile(ir, className, outputDir);
            System.out.println("Compiled " + sourcePath + " -> " + outputDir.resolve(className + ".class"));
        } catch (Exception e) {
            System.err.println("[CompileError] " + e.getMessage());
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
            System.out.print(promptForBuffer(buffer.toString()));
            line = reader.readLine();
            if (line == null) break; // EOF
            if (line.isBlank() && buffer.isEmpty()) continue;
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
            // Bare REPL expressions do not need a trailing ';', so we synthesize one
            // only for parsing and then print the resulting value if it is non-Unit.
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

    static boolean isSubmissionReady(String buffer, String currentLine) {
        ReplInputState state = analyzeInput(buffer);
        if (!state.isBalanced()) {
            return false;
        }

        if (endsWithStatementTerminator(currentLine)) {
            return true;
        }

        try {
            // If the buffered text becomes a valid statement when treated as an
            // expression statement, we can execute it as a bare REPL expression.
            parse(buffer + ";");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String promptForBuffer(String buffer) {
        return buffer.isEmpty() ? "> " : "... ";
    }

    private static ReplInputState analyzeInput(String src) {
        int parenDepth = 0;
        int braceDepth = 0;
        boolean inString = false;
        boolean escaping = false;

        // This lightweight scan lets the REPL distinguish "keep reading" from
        // "ready to parse" without surfacing noisy parser errors mid-input.
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);

            if (inString) {
                if (escaping) {
                    escaping = false;
                    continue;
                }
                if (c == '\\') {
                    escaping = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            if (c == '(') parenDepth++;
            if (c == ')' && parenDepth > 0) parenDepth--;
            if (c == '{') braceDepth++;
            if (c == '}' && braceDepth > 0) braceDepth--;
        }

        return new ReplInputState(parenDepth, braceDepth, inString);
    }

    private static boolean endsWithStatementTerminator(String line) {
        String trimmed = line.trim();
        return trimmed.endsWith(";") || trimmed.endsWith("}");
    }

    private record ReplInputState(int parenDepth, int braceDepth, boolean inString) {
        boolean isBalanced() {
            return parenDepth == 0 && braceDepth == 0 && !inString;
        }
    }

    private static String defaultClassName(Path sourcePath) {
        String fileName = sourcePath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        StringBuilder out = new StringBuilder();
        boolean capitalizeNext = true;
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (out.isEmpty() && Character.isDigit(c)) {
                    out.append('M');
                }
                out.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            } else {
                capitalizeNext = true;
            }
        }
        return out.isEmpty() ? "MynProgram" : out.toString();
    }
}
