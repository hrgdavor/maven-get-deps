# get_deps: Dependency Resolution (Zig Edition)

The `get_deps` tool is an ultra-fast, zero-dependency implementation written in Zig. It focuses on the most critical deployment tasks: resolution, artifact management, and classpath generation. It can not resolve transitive dependences, you need to solve that step during build with maven.

## Usage

### 1. Generate a CLASSPATH string
Perfect for use in bash/PowerShell scripts to inject dependencies into an environment variable.
```sh
# Linux/macOS
CP_STRING=$(get_deps deps -i deps.txt -cf path --classpath --cache ~/.m2/repository)
export CLASSPATH="app.jar:$CP_STRING"

# Windows (PowerShell)
$CP_STRING = .\get_deps.exe deps -i deps.txt -cf path --classpath --cache $HOME\.m2\repository
$env:CLASSPATH = "app.jar;" + $CP_STRING
```

### 2. Download missing Jars
The tool can act as a lightweight "downloader" for your shared repository.
```powershell
get_deps deps --input dependencies.txt --download --cache /opt/shared/lib
```
This will check `/opt/shared/lib` for each dependency in `dependencies.txt`. If a JAR is missing, it will be downloaded from Maven Central.

### 3. Format Conversion
Convert a list of paths back to colon format for easier reading or comparison.
```powershell
get_deps deps -i list_of_paths.txt -cf colon
```

## Input Format (`cp.txt` / `deps.txt`)
The input files define the runtime environment. They include the application itself and its Maven dependencies.

```text
# Local application JAR (resolved relative to cp.txt location)
./app.jar

# Maven dependencies (resolved via the --cache directory)
org.slf4j:slf4j-api:2.0.7
com.fasterxml.jackson.core:jackson-databind:2.15.2
commons-io:commons-io:2.13.0
```

> [!TIP]
> **Comments & Empty Lines**: All input files support shell-style comments starting with `#` and empty lines.

> [!TIP]
> **Relative Paths**: By including `./app.jar` in `cp.txt`, the tool automatically resolves it to the full path of the JAR inside that specific folder.

## Argument Reference
-   `-i, --input <file>`: Input text file containing a list of dependencies.
-   `-cf, --convert-format <format>`: Target format for output (`colon` | `path`).
-   `-cp, --classpath`: Output a single OS-separated CLASSPATH string.
-   `-c, --cache <dir>`: Base directory for paths (defaults to `~/.m2/repository`).
-   `--download`: Automatically download missing JARs from Maven Central to the cache directory.

## Production Example: Java & Systemd
See how to use `get_deps` in a systemd service to dynamically generate classpaths:

```ini
ExecStartPre=/bin/sh -c 'echo "CP=$(./get_deps deps -i current/cp.txt --classpath --cache /opt/shared/m2)" > /run/my-app.env'
EnvironmentFile=/run/my-app.env
ExecStart=/usr/bin/java -cp "${CP}" com.example.Main
```
