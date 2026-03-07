# maven-get-deps

TODO: look into integration with https://github.com/mthmulders/mcs as a PR, or check if it can produce same things.

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

This makes for leaner releases and faster deployments. How exactly you combine this is up to you.

If you have multiple versions of your java binaries available for instances to use, the shared local repository will have the combination of all of the dependencies, but the classpath file in each distributed binary version will help you cherry pick for classpath only those you need. Later you can use the classpath files to clean the shared repo after you remove a version.

You can also rely on `--classpath` mode available in both tools to directly output an OS-friendly formatted `CLASSPATH` string ready to be injected into an environment variable.

Here is an example script in bash:
```sh
LIB_ROOT="/opt/shared/lib"
DEPS_FILE="dependencies.txt"

# Assuming dependencies.txt contains the standard relative paths format:
CP_STRING=$(java -jar maven-get-deps-cli.jar --input "$DEPS_FILE" --convert-format path --classpath --cache "$LIB_ROOT")
# Or using the zig version:
# CP_STRING=$(maven_get_deps -i "$DEPS_FILE" -cf path --classpath --cache "$LIB_ROOT")

export CLASSPATH="app.jar:$CP_STRING"
java com.example.Main
```

Here is an example script in PowerShell:
```powershell
$LIB_ROOT = "C:\opt\shared\lib"
$DEPS_FILE = "dependencies.txt"

# Using Java executable
$CP_STRING = java -jar maven-get-deps-cli.jar --input $DEPS_FILE --convert-format path --classpath --cache $LIB_ROOT
# Or using the Zig executable:
# $CP_STRING = maven_get_deps -i $DEPS_FILE -cf path --classpath --cache $LIB_ROOT

$env:CLASSPATH = "app.jar;" + $CP_STRING
java com.example.Main
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
- `--report`: (Optional) Path to a file to generate a detailed Markdown report of dependency sizes
- `-cp, --classpath`: (Optional) Formats the output array as a single OS-separated CLASSPATH string instead of listing each element on a new line. Handles File path separators properly.

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

The plugin is deployed to Maven Central. You can add it to your `pom.xml` under `<build><plugins>` or run it directly from the command line once resolved.

```powershell
mvn io.github.hrgdavor:maven-get-deps:1.0.0:get-deps [-DdestDir=<DEST_PATH>] [-DcopyJars=true] [-DoutputFile=<OUTPUT_FILE>]
```

### Goal Parameters

- `destDir`: (Optional) A separate directory for listing/copying. If provided, paths will be relative to this folder. If not provided, paths are relative to your local Maven repository.
- `copyJars`: (Optional, default: `false`) Whether to copy dependencies from your local Maven repo to `destDir`. (Only works if `destDir` is provided).
- `outputFile`: (Optional) Save the list to a file.
- `scopes`: (Optional, default: `compile,runtime`) Scopes to include.
- `reportFile` : (Optional) Path to a file to generate a detailed Markdown report of dependency sizes.
- `classpath` : (Optional, default: `false`) Outputs dependencies as a single OS-separated string suitable for the `CLASSPATH` environment variable.
- `cache` : (Optional) Path to your local maven repository. Used as the prefix root for paths when `classpath` is true, otherwise uses `~/.m2/repository`.

### Example

```powershell
# Default: List all runtime dependencies relative to your .m2
mvn io.github.hrgdavor:maven-get-deps:1.0.0:get-deps

# Convert dependency output into a CLASSPATH string:
mvn io.github.hrgdavor:maven-get-deps:1.0.0:get-deps -Dclasspath=true

# Copy runtime dependencies to a standalone folder
mvn io.github.hrgdavor:maven-get-deps:1.0.0:get-deps -DdestDir=target/copy -DcopyJars=true
```


# How the tool works

- **Source (Cache)**: This is your local Maven repository (defaults to `~/.m2/repository`). The tool always uses this as the primary source for JARs and POMs to avoid redundant downloads.
- Downloads missing dependencies (most of the time will not have to if you build your project before calling the tool, all dependencies will be in maven local repo)
- **Destination (`destDir`)**: An **optional** standalone directory.
  - If `copyJars` is enabled, artifacts are copied **from** the Source **to** this Destination.
- tool calculates relative paths. The relative path results are **interchangeable** (e.g., `org/apache/maven/...`) as the destination directory follows the standard Maven layout.

## OWASP Fast Dependency Check

For projects that strictly use Maven Central and do not bring in random external JARs, you can use `maven-get-deps` to populate a shared local repository and configure the OWASP Dependency-Check plugin to run significantly faster. By disabling JAR analysis and relying strictly on Maven's dependency graph, the scan time is drastically reduced.

Here is an example configuration for using OWASP:

```xml
<configuration>
    <autoUpdate>false</autoUpdate>
    <dataDirectory>/path/to/shared/h2</dataDirectory>
    
    <!-- This stops it from opening JARs -->
    <archiveAnalyzerEnabled>false</archiveAnalyzerEnabled>
    <jarAnalyzerEnabled>false</jarAnalyzerEnabled>
    
    <!-- This relies strictly on Maven's dependency graph (Fast) -->
    <centralAnalyzerEnabled>true</centralAnalyzerEnabled> 
</configuration>
```

## CVE Report (CLI — `executable` profile only)

The Java CLI (built with `-Pexecutable`) downloads a local OWASP H2 CVE database and queries it
for known vulnerabilities — no network access is performed during the actual scan.

The H2 database is stored in `~/.m2/dependency-check-data` by default and can be pointed elsewhere with
`--cve-data`.

### Step 1: Populate / update the local CVE database

```powershell
# First run (or regular refresh) — downloads 330K+ NVD CVE records
java -jar maven-get-deps-1.0.0-cli.jar --cve-update

# With an NVD API key (highly recommended — avoids rate-limiting, 10× faster)
java -jar maven-get-deps-1.0.0-cli.jar --cve-update --nvd-api-key <YOUR_KEY>

# Custom database location
java -jar maven-get-deps-1.0.0-cli.jar --cve-update --cve-data /shared/cve-db
```

> **Get a free NVD API key** at [nvd.nist.gov/developers/request-an-api-key](https://nvd.nist.gov/developers/request-an-api-key).
> Without a key, downloads still work but are rate-limited and much slower.
>
> Schedule `--cve-update` as a cron job / Task Scheduler entry to keep data current:
> ```
> # Linux/macOS cron (daily at 03:00)
> 0 3 * * * java -jar /opt/maven-get-deps-cli.jar --cve-update --nvd-api-key $NVD_KEY
> ```

### Step 2: Generate the CVE report

```powershell
# From a pom.xml (default --cve-data location used automatically)
java -jar maven-get-deps-1.0.0-cli.jar --pom pom.xml --cve-report cve-report.md

# From a dependency list file
java -jar maven-get-deps-1.0.0-cli.jar --input deps.txt --cve-report cve-report.md

# Custom database location
java -jar maven-get-deps-1.0.0-cli.jar --pom pom.xml --cve-report cve-report.md `
    --cve-data /shared/cve-db
```

### Report format

The report is a two-section markdown file:

**Section 1 — Summary table** (one row per direct dependency):
```
| Direct Dependency | Status | Transitive Issues |
|---|:---:|:---:|
| `org.example:foo:1.0` | ✅ CLEAN | — |
| `log4j:log4j:1.2.17` | ⚠ CVE | 0 with CVEs |
```

**Section 2 — Detailed sections** (one `###` block per direct dependency showing all transitives):
```
### log4j:log4j:1.2.17

| Artifact | Version | CVEs |
|---|---|---|
| `log4j:log4j` | 1.2.17 | CVE-2019-17571, CVE-2022-23302 |
```

# Build & Development

For instructions on building the project, generating native images, and maintaining GraalVM metadata, see [README.dev.md](README.dev.md).

