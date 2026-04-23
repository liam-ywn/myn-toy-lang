package yewintnaing.dev.myn;

import java.util.*;

final class Environment {
    private final Map<String, Slot> values = new HashMap<>();
    private final Environment parent;

    record Slot(Value value, boolean mutable, String typeAnn) {
    }

    Environment() {
        this(null);
    }

    Environment(Environment parent) {
        this.parent = parent;
    }

    Environment parent() {
        return parent;
    }

    void define(String name, Value v) {
        define(name, v, false, null);
    }

    void define(String name, Value v, boolean mutable) {
        define(name, v, mutable, null);
    }

    void define(String name, Value v, boolean mutable, String typeAnn) {

        if (values.containsKey(name)) {
            throw new RuntimeException("Variable already defined in this scope: " + name);
        }

        values.put(name, new Slot(v, mutable, typeAnn));
    }

    /**
     * Assign to the nearest existing binding.
     * - Never creates a new binding.
     * - Preserves mutability flag.
     * - Returns true if updated; false if not found (caller can throw "Undefined variable").
     */
    boolean assign(String name, Value newValue) {
        Slot here = values.get(name);
        if (here != null) {
            if (!here.mutable()) {
                throw new RuntimeException("cannot assign to immutable variable: " + name);
            }

            values.put(name, new Slot(newValue, true, here.typeAnn()));
            return true;
        }
        if (parent != null) {
            return parent.assign(name, newValue);
        }
        return false; // not found anywhere
    }

    boolean isExists(String name) {
        return values.containsKey(name);
    }

    Value get(String name) {
        if (values.containsKey(name)) return values.get(name).value;
        if (parent != null) return parent.get(name);
        throw new RuntimeException("Undefined variable: " + name);
    }

    Slot getSlot(String name) {
        return values.get(name);
    }
}
