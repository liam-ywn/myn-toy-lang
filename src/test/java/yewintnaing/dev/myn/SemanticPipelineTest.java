package yewintnaing.dev.myn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticPipelineTest {

    @Test
    void resolverResolvesLocalsAndFunctions() {
        List<Stmt> program = parse("""
                var x: Int = 1;
                fun show(value: Int): Unit {
                  println(x + value);
                }
                show(2);
                """);

        Resolver.Resolution resolution = new Resolver().resolve(program);

        assertTrue(resolution.localsByName().containsKey("x"));
        assertTrue(resolution.localsByName().containsKey("show"));
        assertEquals(MynType.INT, resolution.localsByName().get("x").type());
        assertEquals(MynType.FUNCTION, resolution.localsByName().get("show").type());
    }

    @Test
    void typeCheckerProducesExpressionTypes() {
        List<Stmt> program = parse("""
                var total: Int = 1 + 2;
                println(typeOf(total));
                """);

        Resolver.Resolution resolution = new Resolver().resolve(program);
        TypeChecker.TypeCheckResult result = new TypeChecker().check(program, resolution);

        assertEquals(MynType.INT, result.locals().get("total"));
        assertTrue(result.expressionTypes().containsValue(MynType.STRING));
    }

    @Test
    void irLowererProducesLinearInstructionStreamForSupportedSubset() {
        List<Stmt> program = parse("""
                var total: Int = 0;
                var i: Int = 0;
                while (i < 3) {
                  total = total + i;
                  i++;
                }
                println(total);
                """);

        Resolver.Resolution resolution = new Resolver().resolve(program);
        TypeChecker.TypeCheckResult types = new TypeChecker().check(program, resolution);
        MynIr.Module ir = new IrLowerer().lower(program, types);

        assertTrue(ir.instructions().stream().anyMatch(instr -> instr instanceof MynIr.Instr.Label));
        assertTrue(ir.instructions().stream().anyMatch(instr -> instr instanceof MynIr.Instr.BranchFalse));
        assertTrue(ir.instructions().stream().anyMatch(instr -> instr instanceof MynIr.Instr.CallBuiltin call && "println".equals(call.name())));
    }

    @Test
    void resolverRejectsUndefinedVariablesEarly() {
        CompileError error = assertThrows(CompileError.class, () ->
                new Resolver().resolve(parse("""
                        println(missing);
                        """)));

        assertTrue(error.getMessage().contains("Undefined variable: missing"));
    }

    private static List<Stmt> parse(String src) {
        Lexer lexer = new Lexer(src);
        Parser parser = new Parser(lexer.scan());
        return parser.parse();
    }
}
