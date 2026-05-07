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

class JvmIrCompilerTest {

    @Test
    void compilesIrLoweredModuleToRunnableClass() throws Exception {
        Path outDir = Files.createTempDirectory("myn-ir-compiled");
        String className = "IrCompilerSmoke";
        String source = """
                println("IR compiler demo");
                var total: Int = 0;
                var i: Int = 0;
                while (i < 4) {
                  total = total + i;
                  i++;
                }
                if (total == 6) {
                  println("sum = " + toString(total));
                }
                println(typeOf(total));
                println(len("myn"));
                """;

        List<Stmt> program = parse(source);
        Resolver.Resolution resolution = new Resolver().resolve(program);
        TypeChecker.TypeCheckResult types = new TypeChecker().check(program, resolution);
        MynIr.Module module = new IrLowerer().lower(program, types);

        new JvmIrCompiler().compile(module, className, outDir);

        assertEquals("IR compiler demo%nsum = 6%nInt%n3%n".formatted(), runCompiledClass(outDir, className));
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
