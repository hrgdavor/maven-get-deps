# maven-get-deps Development Guide

This document describes how to build, test, and maintain the `maven-get-deps` project.

# Build

To build the project, ensure you have Java 17+ and Maven installed.

The project is structured with two profiles:
1. The **Maven Plugin (Mojo)**: Built by default to keep the deployable artifact thin.
2. The **CLI Executable**: Built via the `executable` profile, including CLI parsing logic and outputting a fat JAR.

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"

# Build the Maven plugin (default profile)
mvn clean install -DskipTests

# Build the CLI executable JAR
mvn clean package -Pexecutable -DskipTests
```

# GraalVM Native Image Build

On windows difference to run the tool on pom.xml of this project is significant (5x). 
- 300ms -  `Measure-Command { java -jar .\target\maven-get-deps-1.0.0-cli.jar}`
- 60ms - `Measure-Command { .\target\maven-get-deps.exe | Out-Default }`

You can build a standalone native executable for the CLI using GraalVM. Note that this requires both `executable` and `native` profiles.

#### Prerequisites (Windows)
1.  **GraalVM JDK**: You have it installed at `C:\Program Files\Java\graalvm-jdk-25+37.1`.
2.  **Visual Studio 2022**: Required for the native toolchain (`cl.exe`).
    - Open **Visual Studio Installer**.
    - Select **Desktop development with C++** workload.
    - Under **Optional** components (on the right), ensure these are checked:
        - **MSVC v143 - VS 2022 C++ x64/x86 build tools (Latest)**
        - **Windows 10/11 SDK** (e.g., Windows 11 SDK 10.0.22621.0)
    - Under **Language packs**, ensure **English** is installed (GraalVM might fail to parse error messages in other languages).
3.  **Developer PowerShell**: Run the build from a "Developer PowerShell for VS 2022" or ensure `cl.exe` is in your PATH (usually handled by the Developer PowerShell).

#### Steps
1.  Set your environment:
    ```powershell
    $env:JAVA_HOME = "C:\Program Files\Java\graalvm-jdk-25+37.1"
    $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
    ```
2.  Build the native image:
    ```powershell
    mvn clean package -Pexecutable,native -DskipTests
    ```
    The resulting binary will be located at `target/maven-get-deps.exe`.

### Linux Native Image Build (via WSL)

You can build a Linux binary using Windows Subsystem for Linux (WSL).

#### Prerequisites (WSL/Ubuntu)
1.  **Install Build Tools**:
    ```bash
    sudo apt-get update
    sudo apt-get install -y build-essential libz-dev zlib1g-dev
    ```
2.  **Install GraalVM**:
    ```bash
    sudo mkdir -p /opt/graalvm
    curl -L https://download.oracle.com/graalvm/21/latest/graalvm-jdk-21_linux-x64_bin.tar.gz | sudo tar -xz -C /opt/graalvm --strip-components=1
    ```
3.  **Install Maven**: `sudo apt-get install -y maven`.

#### Steps
1.  Set your environment:
    ```bash
    export JAVA_HOME=/opt/graalvm
    export PATH=$JAVA_HOME/bin:$PATH
    ```
2.  Build the native image:
    ```bash
    mvn clean package -Pnative -DskipTests
    ```
    The resulting binary will be `target/maven-get-deps`.

### Cross-Platform Caveats
- **No Cross-Compilation**: GraalVM `native-image` does not support building a Linux binary from Windows (or vice-versa). You must build on the target OS.
- **GLIBC Dependency**: By default, Linux binaries are dynamically linked against `glibc`. A binary built on Ubuntu might not run on very old Linux distros or distros using `musl` (like Alpine) unless built with static linking.
- **Resources**: Ensure `resource-config.json` is maintained if new Maven resources (like Super POMs) are needed at runtime.

### Maintaining Native Image Configuration

GraalVM Native Image builds use Ahead-of-Time (AOT) compilation, which requires that all dynamic features of the Java language (like reflection, dynamic proxies, and resource loading) are identified and configured at build time. Since `maven-get-deps` relies on Maven Resolver (Aether), Jackson, and H2, which use reflection heavily, we must maintain a set of "reachability metadata" files in `src/main/resources/META-INF/native-image`.

#### GraalVM Tracing Agent
The project uses the **GraalVM Tracing Agent** to capture these requirements by observing the application's behavior during a normal JVM run.

#### Metadata Merger Utility
To prevent overwriting manually tuned metadata (like specific reflection entries for Maven models) with new agent-generated data, we use the `MetadataMerger` Java utility. This tool performs a smart merge of `reflection` and `resources` configurations, ensuring that:
1. Unique entries from both old and new files are preserved.
2. If an entry exists in both, the newer one (from the agent) is preferred.
3. The resulting JSON is alphabetically sorted to keep Git diffs clean.

#### Steps to update metadata:
1.  **Run the Agent**: Execute the CLI JAR with the tracing agent enabled:
    ```powershell
    # Windows
    $env:JAVA_HOME = "C:\Program Files\Java\graalvm-jdk-25+37.1"
    & "$env:JAVA_HOME\bin\java.exe" -agentlib:native-image-agent=config-output-dir=agent-output -jar target/maven-get-deps-1.0.0-cli.jar --pom pom.xml --no-copy
    ```
3.  **Merge the Metadata**: Use the internal merger utility to combine the results:
    ```powershell
    # Run the merger using the CLI jar (GraalVM Java 21+ required for execution)
    $OLD_META = "src/main/resources/META-INF/native-image/io.github.hrgdavor/maven-get-deps/reachability-metadata.json"
    $NEW_META = "agent-output/reachability-metadata.json"
    
    java -cp target/maven-get-deps-1.0.0-cli.jar hr.hrg.maven.getdeps.MetadataMerger `
        $OLD_META $NEW_META $OLD_META
    ```
4.  **Rebuild**: Run the native build again. The plugin will automatically pick up configurations from `META-INF/native-image`.
