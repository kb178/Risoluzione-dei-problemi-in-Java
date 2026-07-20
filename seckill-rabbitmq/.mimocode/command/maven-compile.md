---
description: Set JAVA_HOME to JDK 17 and run Maven compile. Pass --clean for clean build, --quiet to suppress output.
---

# Maven Compile Command

Sets JAVA_HOME to JDK 17 before running Maven, since the system default is JDK 8 which cannot compile Spring Boot 3.x projects.

## Usage

```
maven-compile [--clean] [--quiet]
```

## Default Behavior

- JAVA_HOME = `$env:USERPROFILE\.jdks\ms-17.0.15`
- Runs `mvn compile`
- Verbose output (compiler warnings, errors)

## Flags

- `--clean` — adds `clean` phase before compile (`mvn clean compile`)
- `--quiet` — adds `-q` flag to suppress non-error output

## Execution

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-17.0.15"; mvn compile
```

## Notes

- PowerShell execution policy may block npm scripts; use `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` if needed
- The JAVA_HOME path `$env:USERPROFILE\.jdks\ms-17.0.15` is specific to this machine; adjust for other environments
- Projects using Spring Boot 3.x require JDK 17+; the system default JDK 8 cannot compile them
- Verify with `java -version` after setting JAVA_HOME if compilation fails unexpectedly
