package yewintnaing.dev.myn;

enum MynType {
    INT("Int"),
    STRING("String"),
    BOOLEAN("Boolean"),
    UNIT("Unit"),
    FUNCTION("Function"),
    ANY("Any");

    private final String displayName;

    MynType(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }

    static MynType fromAnnotation(String annotation) {
        if (annotation == null) {
            return null;
        }
        return switch (annotation) {
            case "Int" -> INT;
            case "String" -> STRING;
            case "Boolean" -> BOOLEAN;
            case "Unit" -> UNIT;
            case "Any" -> ANY;
            default -> throw new CompileError("Unknown type annotation: " + annotation);
        };
    }
}
