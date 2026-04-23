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

    @Test
    void replWaitsForOpenBlockBeforeSubmitting() {
        assertTrue(!Main.isSubmissionReady("""
                if (true) {
                  println(1);
                """, "  println(1);"));

        assertTrue(Main.isSubmissionReady("""
                if (true) {
                  println(1);
                }
                """, "}"));
    }

    @Test
    void replUsesContinuationPromptForBufferedInput() {
        assertEquals("> ", Main.promptForBuffer(""));
        assertEquals("... ", Main.promptForBuffer("if (true) {\n"));
    }

    @Test
    void variableAnnotationsAreCheckedOnDeclarationAndAssignment() {
        RuntimeException declarationError = assertThrows(RuntimeException.class, () -> runProgram("""
                let x: Int = "oops";
                """));

        assertTrue(declarationError.getMessage().contains("[line 1, col"));
        assertTrue(declarationError.getMessage().contains("expected Int but got String"));

        RuntimeException assignmentError = assertThrows(RuntimeException.class, () -> runProgram("""
                var x: Int = 1;
                x = "oops";
                """));

        assertTrue(assignmentError.getMessage().contains("[line 2, col"));
        assertTrue(assignmentError.getMessage().contains("variable 'x'"));
        assertTrue(assignmentError.getMessage().contains("expected Int but got String"));
    }

    @Test
    void functionParameterAnnotationsAreChecked() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> runProgram("""
                fun label(flag: Boolean): Unit {
                  println(flag);
                }
                label(1);
                """));

        assertTrue(error.getMessage().contains("[line 4, col"));
        assertTrue(error.getMessage().contains("parameter 'flag'"));
        assertTrue(error.getMessage().contains("expected Boolean but got Int"));
    }

    @Test
    void functionReturnAnnotationsAreChecked() {
        RuntimeException wrongReturnType = assertThrows(RuntimeException.class, () -> runProgram("""
                fun bad(): Int {
                  return "oops";
                }
                bad();
                """));

        assertTrue(wrongReturnType.getMessage().contains("[line 2, col"));
        assertTrue(wrongReturnType.getMessage().contains("return value"));
        assertTrue(wrongReturnType.getMessage().contains("expected Int but got String"));

        RuntimeException missingReturn = assertThrows(RuntimeException.class, () -> runProgram("""
                fun bad(): Boolean {
                  println("hi");
                }
                bad();
                """));

        assertTrue(missingReturn.getMessage().contains("[line 4, col"));
        assertTrue(missingReturn.getMessage().contains("return value"));
        assertTrue(missingReturn.getMessage().contains("expected Boolean but got Unit"));
    }

    @Test
    void unitAnnotationsAcceptNoInitializerAndUnitReturn() {
        String output = runProgram("""
                let marker: Unit;
                fun log(flag: Boolean): Unit {
                  if (flag) {
                    println("ok");
                    return;
                  }
                }
                log(true);
                """);

        assertEquals("ok%n".formatted(), output);
    }

    @Test
    void typedBuiltinsAcceptAnyValueAndReturnUnit() {
        String output = runProgram("""
                let marker: Unit;
                println(1);
                println("hi");
                println(true);
                print(marker);
                """);

        assertEquals("1%nhi%ntrue%nUnit".formatted(), output);
    }

    @Test
    void typedBuiltinsStillEnforceArity() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> runProgram("""
                println();
                """));

        assertTrue(error.getMessage().contains("[line 1, col"));
        assertTrue(error.getMessage().contains("expected 1 arguments but got 0"));
    }

    @Test
    void utilityBuiltinsReturnTypedResults() {
        String output = runProgram("""
                println(typeOf(1));
                println(typeOf("hi"));
                println(typeOf(true));
                println(toString(123));
                println(len("myn"));
                """);

        assertEquals("Int%nString%nBoolean%n123%n3%n".formatted(), output);
    }

    @Test
    void utilityBuiltinsEnforceTheirParameterTypes() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> runProgram("""
                len(123);
                """));

        assertTrue(error.getMessage().contains("[line 1, col"));
        assertTrue(error.getMessage().contains("parameter 'value'"));
        assertTrue(error.getMessage().contains("expected String but got Int"));
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
