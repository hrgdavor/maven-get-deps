# maven-get-deps

A standalone tool to resolve and download Maven dependencies to a specific local folder, and also genrate classpath file for scripting. Uses the same structure as any maven repository(even your local maven repository has the same structure).


## Why

To deploy an app to remote servers you need your classes and dependencies available to run the app. Usually means copying dependencies to a lib folder alongside your jar, or even worse, fat jars.

Sadly with Spring bloat, and Hibernate, even a moderately sized Java app that is like **500KB** jar, that does some crud in database, has few entities and a bit of logic, draws in **50MB** of dependencies. And it easily grows over **100MB**. So for those classic cases it is really a **200x** size diff for deploying a newer version of an app. And it is even worse for microservices, as even less code will be in each service versus standard pile of dependencies.

### You really should optimize the process

This tool is specifically meant to be used to optimize how you deploy, for testing, staging and production.

The idea is to:

- create a shared dependency folder that you can provision to servers 
    - using a network drive 
    - or by copying it to each server (rsync, scp, git, etc.)
- use the folder as a local repository (sum of runtime dependencies of your app versions)
- use this tool to generate a list of dependencies in a file that you deploy along with your jar
- use the file to start the applications by a simple script without needing to install or copy those dependencies to application folder

This makes for leaner releases and faster deployments. How exactly you combi this is up to you.

If you have multiple versions of your java binaries available for instances to use, the shared local repository will have the combination of all of the dependencies, but the classpath file in each distributed binary version will help you cherry pick for classpath only those you need. Later you can use the classpath files to clean the shared repo after you remove a version.

Here is an example script in bash:
```sh
LIB_ROOT="/opt/shared/lib"
DEPS_FILE="dependencies.txt"
CP="app.jar"
while IFS= read -r line; do
  CP="$CP:$LIB_ROOT/$line"
done < "$DEPS_FILE"

export CLASSPATH="$CP"; java com.example.Main
```

Here is an example script in PowerShell:
```sh
$LIB_ROOT = "C:\opt\shared\lib"
$DEPS_FILE = "dependencies.txt"
$CP = "app.jar"
Get-Content $DEPS_FILE | ForEach-Object {
  $CP += ";$LIB_ROOT/$_"
}

$env:CLASSPATH = $CP ; java com.example.Main
```

>Notice, I have just learned while doing this project (something I should have know ages ago) that java will look if CLASSPATH variable is set and use it. This removes noise of the huge classpath from process view when using `ps`, and may have some other benefits.

# Download

Manually download from https://github.com/hrgdavor/maven-get-deps/releases.

- `maven-get-deps-linux-x64.tar.gz` generic linux binary in tar.gz
- `maven-get-deps-windows-x64.zip` - windows in zip file
- `maven-get-deps-cli.jar` - fat jar that can be executed with `java -jar`

To automate download of latest release artifacts (CLI-jar, win64 exe, Linux binary) with script you can use this URL https://api.github.com/repos/hrgdavor/maven-get-deps/releases/latest and parse the JSON.

# Standalone CLI Usage

Run the tool binary for linux/windows or using `java -jar target/maven-get-deps-1.0.0-cli.jar` and add parameters:

```powershell
--pom <YOUR_POM_PATH> [--dest-dir <DEST_PATH>] [--cache <CACHE_M2_PATH>] [--no-copy]
```

### Arguments

- `-p, --pom <arg>`:  (default `pom.xml`) Path to the pom  to analyze.
- `-o, --output <arg>`: (Optional) Path to a file for the dependency list, or will be printed out.
- `-d, --dest-dir <arg>`: (Optional) Destination directory for jar files. 
- `-n, --no-copy`: (Optional) Disable copying. Even if `dest-dir` is provided, files will not be copied (only path relativization will use it).
- `-c, --cache <arg>`: (default  `~/.m2/repository`) Local repository to use as a **source**.
- `-s, --scopes <arg>`: (Optional, default: `compile,runtime`) Comma-separated list of scopes to include.
- `--report` - (Optional) Path to a file to generate a detailed Markdown report of dependency sizes

#### Example

```powershell
# Default: List paths relative to repositry root (no copying)
java -jar target/maven-get-deps-1.0.0-cli.jar --pom pom.xml

# Copy to a separate folder and list paths relative to repositry root
java -jar target/maven-get-deps-1.0.0-cli.jar --pom pom.xml --dest-dir ./out
```

## Dependency Size Report

- **CLI**: `--report report.md`
- **Maven Plugin**: `-DreportFile=report.md`

#### How the Report Works
The report attributes size "incrementally" following the order of dependencies in your `pom.xml`:
1.  **Unique Contribution**: For each top-level dependency, the tool sums the size of its own JAR and all its transitive dependencies.
2.  **Deduplication**: If a transitive dependency is shared by multiple top-level dependencies, it is **only counted once**—attributing it to the *first* dependency in the POM that requires it.
3.  **Format**: The output is a markdown table with `Size (KB)` and `Dependency`, followed by the total size of all unique artifacts.

This approach helps you identify which specific top-level dependencies are responsible for the bulk of your application's "weight" after transitives are factored in.

This is how it looks like for this project (at the time of writing):

| Size (KB) | Dependency                                                      |
|----------:|:----------------------------------------------------------------|
|       156 | org.apache.maven.resolver:maven-resolver-api:1.9.18             |
|        53 | org.apache.maven.resolver:maven-resolver-spi:1.9.18             |
|       194 | org.apache.maven.resolver:maven-resolver-util:1.9.18            |
|       314 | org.apache.maven.resolver:maven-resolver-impl:1.9.18            |
|        43 | org.apache.maven.resolver:maven-resolver-connector-basic:1.9.18 |
|        60 | org.apache.maven.resolver:maven-resolver-transport-http:1.9.18  |
|        24 | org.apache.maven.resolver:maven-resolver-supplier:1.9.18        |
|        77 | org.apache.maven:maven-resolver-provider:3.9.6                  |
|       215 | org.apache.maven:maven-model:3.9.6                              |
|        71 | commons-cli:commons-cli:1.6.0                                   |
|        58 | org.slf4j:slf4j-simple:1.7.36                                   |

> Total size: 1297940 bytes (1.24 MB)

# Maven Plugin Usage

Once installed to your local repository (via `mvn install`), you can run the tool as a Maven plugin goal:

```powershell
mvn io.github.hrgdavor:maven-get-deps:1.0.0:get-deps [-DdestDir=<DEST_PATH>] [-DcopyJars=true] [-DoutputFile=<OUTPUT_FILE>]
```

### Goal Parameters

- `destDir`: (Optional) A separate directory for listing/copying. If provided, paths will be relative to this folder. If not provided, paths are relative to your local Maven repository.
- `copyJars`: (Optional, default: `false`) Whether to copy dependencies from your local Maven repo to `destDir`. (Only works if `destDir` is provided).
- `outputFile`: (Optional) Save the list to a file.
- `scopes`: (Optional, default: `compile,runtime`) Scopes to include.
- `reportFile` : (Optional) Path to a file to generate a detailed Markdown report of dependency sizes

### Example

```powershell
# Default: List all runtime dependencies relative to your .m2
mvn io.github.hrgdavor:maven-get-deps:get-deps

# Copy runtime dependencies to a standalone folder
mvn io.github.hrgdavor:maven-get-deps:get-deps -DdestDir=target/copy -DcopyJars=true
```


# How the tool works

- **Source (Cache)**: This is your local Maven repository (defaults to `~/.m2/repository`). The tool always uses this as the primary source for JARs and POMs to avoid redundant downloads.
- Downloads missing dependencies (most of the time will not have to if you build your project before calling the tool, all dependencies will be in maven local repo)
- **Destination (`destDir`)**: An **optional** standalone directory.
  - If `copyJars` is enabled, artifacts are copied **from** the Source **to** this Destination.
- tool calculates relative paths. The relative path results are **interchangeable** (e.g., `org/apache/maven/...`) as the destination directory follows the standard Maven layout.



# Build

To build the project, ensure you have Java 17+ and Maven installed.

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
mvn clean package -DskipTests
```

# GraalVM Native Image Build

On windows difference to run the tool on pom.xml of this project is significant (5x). 
- 300ms -  `Measure-Command { java -jar .\target\maven-get-deps-1.0.0-cli.jar}`
- 60ms - `Measure-Command { .\target\maven-get-deps.exe | Out-Default }`

You can build a standalone native executable for the CLI using GraalVM.

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
    mvn clean package -Pnative -DskipTests
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

If you change the code (especially logic involving Maven Resolver or reflection) or add new dependencies, you may need to update the native image metadata.

The project uses the **GraalVM Tracing Agent** to capture reflection and resource requirements (like the `pom-4.0.0.xml` Super POM).

#### Steps to update metadata:
1.  **Run the Agent**: Execute the CLI JAR with the tracing agent enabled:
    ```powershell
    # Windows
    $env:JAVA_HOME = "C:\Program Files\Java\graalvm-jdk-25+37.1"
    & "$env:JAVA_HOME\bin\java.exe" -agentlib:native-image-agent=config-output-dir=agent-output -jar target/maven-get-deps-1.0.0-cli.jar --pom pom.xml --no-copy
    ```
2.  **Copy the Results**: Sync the generated files to the project resources:
    ```powershell
    # Copy agent results to the standard GraalVM metadata location
    mkdir src/main/resources/META-INF/native-image/io.github.hrgdavor/maven-get-deps
    copy agent-output/* src/main/resources/META-INF/native-image/io.github.hrgdavor/maven-get-deps/
    ```
3.  **Rebuild**: Run the native build again. The plugin will automatically pick up configurations from `META-INF/native-image`.

