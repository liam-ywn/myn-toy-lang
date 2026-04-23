# 🧩 Myn — My Kotlin-style scripting language (learning interpreter project)

**Myn** is my personal learning project — a small interpreter for a Kotlin-like scripting language written entirely in Java.  
It’s built to help me **learn how parsers, interpreters, and execution environments work** from scratch.

It supports variables (`let` / `var`), functions, conditionals, loops, and a basic REPL for experimentation.

> 📄 Example script: [`src/main/resources/hello.myn`](src/main/resources/hello.myn)
> ▶️ Runnable script samples: [`src/main/resources/scripts/`](src/main/resources/scripts)
> 💬 REPL multiline samples: [`src/main/resources/repl/`](src/main/resources/repl) (paste into the REPL; these are not intended to be run directly as script files)
> ☕ JVM compiler demo: [`src/main/resources/scripts/compiler-demo.myn`](src/main/resources/scripts/compiler-demo.myn)

---

## 🎯 Project Purpose

- Understand how **lexers**, **parsers**, and **interpreters** connect.
- Learn about **scopes**, **mutability**, **closures**, and **runtime environments**.
- Eventually evolve Myn into a small bytecode-based VM.

> ⚠️ This project is a **learning sandbox**, not a production language.
> Code is intentionally clean and explicit for educational purposes.

## 🔧 Builtins

- `print(value: Any): Unit`
- `println(value: Any): Unit`
- `typeOf(value: Any): String`
- `toString(value: Any): String`
- `len(value: String): Int`

## 🏭 Compiler Milestone

The first JVM compiler milestone can emit a single `.class` with a `main` method for a subset of Myn scripts.

Current compiled subset:

- top-level statements
- `let` / `var` locals
- assignment
- `if`
- `while`
- integer, string, and boolean expressions
- builtin calls to `print`, `println`, `typeOf`, `toString`, and `len`

Not compiled yet:

- user-defined functions
- closures
- `Unit` locals
- full feature parity with the interpreter

Example:

```bash
./gradlew run --args='--compile src/main/resources/scripts/compiler-demo.myn build/myn'
java -cp build/myn CompilerDemo
```

---

## 🏗️ Tech Stack

| Layer | Tool / Concept |
|-------|----------------|
| Language | Java 23 |
| Build | Gradle (Kotlin DSL) |
| Toolchain | Managed automatically via Gradle |
| Entry Point | `yewintnaing.dev.myn.Main` |
| Syntax Style | Kotlin-inspired, dynamically evaluated |

---

## ⚙️ Requirements

- **Java JDK 23** — Gradle toolchains will auto-install it if missing.  
  JDK 21+ may also work.
- **Gradle** — use the included wrapper (`./gradlew`, `gradlew.bat`).
- **OS** — macOS / Linux / Windows.
- **Internet** — first build downloads dependencies and JDK.

---

## 🚀 Getting Started

### 1. Clone & Build

```bash
git clone <your-repo-url>
cd myn
./gradlew build
./gradlew run --args='src/main/resources/hello.myn'
./gradlew run --args='src/main/resources/scripts/multiline-if-script.myn'
```
