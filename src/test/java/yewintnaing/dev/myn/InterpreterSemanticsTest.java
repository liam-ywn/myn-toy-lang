package yewintnaing.dev.myn;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterpreterSemanticsTest {

    @Test
    void unaryOperatorsEvaluateCorrectly() {
        String output = runProgram("""
                println(-1);
                println(!false);
                println(!0);
                """);

        assertEquals("-1%ntrue%ntrue%n".formatted(), output);
    }

    @Test
    void incrementsUseVariableSemantics() {
        String output = runProgram("""
                var x = 1;
                println(x++);
                println(x);
                println(++x);
                println(x);
                """);

        assertEquals("1%n2%n3%n3%n".formatted(), output);
    }

    @Test
    void prefixIncrementRequiresVariableTarget() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parse("""
                println(++1);
                """));

        assertTrue(error.getMessage().contains("Invalid prefix increment/decrement target"));
    }

    @Test
    void postfixIncrementRequiresVariableTarget() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parse("""
                println((1 + 2)++);
                """));

        assertTrue(error.getMessage().contains("Invalid postfix increment/decrement target"));
    }

    @Test
    void functionCallsRequireExactArity() {
        RuntimeException tooFew = assertThrows(RuntimeException.class, () -> runProgram("""
                fun add(a: Int, b: Int): Int {
                  return a + b;
                }
                println(add(1));
                """));

        assertTrue(tooFew.getMessage().contains("expected 2 arguments but got 1"));

        RuntimeException tooMany = assertThrows(RuntimeException.class, () -> runProgram("""
                fun add(a: Int, b: Int): Int {
                  return a + b;
                }
                println(add(1, 2, 3));
                """));

        assertTrue(tooMany.getMessage().contains("expected 2 arguments but got 3"));
    }

    @Test
    void decimalLiteralsAreRejectedUntilTheyAreImplemented() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> new Lexer("let x = 1.5;").scan());

        assertTrue(error.getMessage().contains("Only Int literals are supported right now"));
    }

    @Test
    void interpreterStatePersistsAcrossExecCalls() {
        Interpreter interpreter = new Interpreter();

        runProgram(interpreter, """
                var x = 1;
                """);

        String output = runProgram(interpreter, """
                x = x + 2;
                println(x);
                """);

        assertEquals("3%n".formatted(), output);
    }

    @Test
    void replExecutionReturnsBareExpressionValue() {
        Interpreter interpreter = new Interpreter();

        runProgram(interpreter, """
                var x = 2;
                """);

        String output = runReplProgram(interpreter, """
                x + 3;
                """);

        assertEquals("5%n".formatted(), output);
    }

    @Test
    void replExecutionDoesNotPrintUnitResults() {
        Interpreter interpreter = new Interpreter();

        String output = runReplProgram(interpreter, """
                var x = 2;
                """);

        assertEquals("", output);
    }

    private static List<Stmt> parse(String src) {
        Lexer lexer = new Lexer(src);
        Parser parser = new Parser(lexer.scan());
        return parser.parse();
    }

    private static String runProgram(String src) {
        return runProgram(new Interpreter(), src);
    }

    private static String runProgram(Interpreter interpreter, String src) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try (PrintStream capture = new PrintStream(out, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            interpreter.exec(parse(src));
        } finally {
            System.setOut(originalOut);
        }

        return out.toString(StandardCharsets.UTF_8);
    }

    private static String runReplProgram(Interpreter interpreter, String src) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try (PrintStream capture = new PrintStream(out, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            Value result = interpreter.execForRepl(parse(src));
            if (!(result instanceof Value.UnitV)) {
                System.out.println(Interpreter.stringify(result));
            }
        } finally {
            System.setOut(originalOut);
        }

        return out.toString(StandardCharsets.UTF_8);
    }
}
