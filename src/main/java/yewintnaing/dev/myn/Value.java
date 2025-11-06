package yewintnaing.dev.myn;

import java.util.List;

sealed interface Value
        permits Value.IntV, Value.BoolV, Value.StrV, Value.UnitV, Value.FuncV, Value.NativeV {

    // Integer value
    record IntV(long v) implements Value {
    }

    // Boolean value
    record BoolV(boolean v) implements Value {
    }

    // String value
    record StrV(String v) implements Value {
    }

    // Unit (void)
    record UnitV() implements Value {
        @Override
        public String toString() {
            return "Unit";
        }
    }

    // User-defined function (captures closure)
    record FuncV(List<String> params, List<String> typeAnns, List<Stmt> body, Environment closure) implements Value {
    }

    // Native host function (like print, println)
    record NativeV(int arity, java.util.function.Function<List<Value>, Value> call) implements Value {
    }
}