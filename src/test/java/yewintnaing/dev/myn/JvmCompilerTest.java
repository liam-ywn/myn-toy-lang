package yewintnaing.dev.myn;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JvmCompilerTest {

    @Test
    void compilesTopLevelScriptToRunnableClass() throws Exception {
        Path outDir = Files.createTempDirectory("myn-compiled");
        String className = "CompilerSmoke";
        String source = """
                println("Compiler demo");
                var total: Int = 0;
                var i: Int = 0;
                while (i < 4) {
                  total = total + i;
                  i++;
                }
                println(total);
                println(typeOf(total));
                println(len("myn"));
                """;

        new JvmCompiler().compile(parse(source), className, outDir);

        assertEquals("Compiler demo%n6%nInt%n3%n".formatted(), runCompiledClass(outDir, className));
    }

    @Test
    void rejectsUserDefinedFunctionsInFirstCompilerMilestone() throws Exception {
        Path outDir = Files.createTempDirectory("myn-compiled");
        CompileError error = assertThrows(CompileError.class, () ->
                new JvmCompiler().compile(parse("""
                        fun add(a: Int, b: Int): Int {
                          return a + b;
                        }
                        println(add(1, 2));
                        """), "UnsupportedFunctions", outDir));

        assertEquals(true, error.getMessage().contains("User-defined functions are not supported"));
    }

    private static List<Stmt> parse(String src) {
        Lexer lexer = new Lexer(src);
        Parser parser = new Parser(lexer.scan());
        return parser.parse();
    }

    private static String runCompiledClass(Path outDir, String className) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try (PrintStream capture = new PrintStream(out, true, StandardCharsets.UTF_8);
             URLClassLoader loader = new URLClassLoader(new URL[]{outDir.toUri().toURL()})) {
            System.setOut(capture);
            Class<?> cls = Class.forName(className, true, loader);
            cls.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(originalOut);
        }

        return out.toString(StandardCharsets.UTF_8);
    }
}
