package yewintnaing.dev.myn;

import java.util.List;
import java.util.Map;

sealed interface MynIr permits MynIr.Module, MynIr.Instr {
    record Module(List<Instr> instructions, Map<String, MynType> locals) implements MynIr {
    }

    sealed interface Instr extends MynIr
            permits Instr.Const, Instr.Move, Instr.Binary, Instr.Unary,
                    Instr.CallBuiltin, Instr.Label, Instr.Jump, Instr.BranchFalse {

        record Const(String target, Object value, MynType type) implements Instr {
        }

        record Move(String target, String source) implements Instr {
        }

        record Binary(String target, String left, String op, String right, MynType type) implements Instr {
        }

        record Unary(String target, String op, String operand, MynType type) implements Instr {
        }

        record CallBuiltin(String target, String name, List<String> args, MynType type) implements Instr {
        }

        record Label(String name) implements Instr {
        }

        record Jump(String targetLabel) implements Instr {
        }

        record BranchFalse(String conditionTemp, String targetLabel) implements Instr {
        }
    }
}
