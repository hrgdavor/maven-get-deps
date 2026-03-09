# maven-get-deps (Zig Edition)

The Zig version of `maven-get-deps` is a ultra-fast, zero-dependency companion tool designed for high-performance deployment environments, CI/CD pipelines, and scenarios where a JVM is not yet available or desired.

It is als a powerful bridge for those who may still need **Docker** but want to keep images thin by managing a shared Maven cache across containers.

- [See the Docker & Kubernetes Integration Guide](README.docker.md)

## Why use the Zig version?

1.  **Speed**: Execution time is often **5x-10x faster** than the Java version for metadata-only tasks (like format conversion or classpath generation).
2.  **Zero Dependencies**: It is a single standalone binary. You don't need Java installed to run it.
3.  **Efficiency**: Extremely low memory footprint, making it ideal for large-scale container deployments or resource-constrained environments.
4.  **Deployment Companion**: Perfect for generating `CLASSPATH` variables in startup scripts without the "cold start" overhead of a JVM.

## Features

-   **Format Conversion**: Convert between Maven Path format and Gradle-style Colon format.
-   **CLASSPATH Generation**: Instantly generate OS-specific classpath strings.
-   **Jar Downloading**: Automated retrieval of missing artifacts directly from Maven Central.
-   **Revision Management**: Atomic symlink swaps, version tracking, and post-deployment triggers for zero-downtime application updates.

## Usage

### 1. Generate a CLASSPATH string
Perfect for use in bash/PowerShell scripts to inject dependencies into an environment variable.
```sh
# Linux/macOS
CP_STRING=$(maven_get_deps -i deps.txt -cf path --classpath --cache ~/.m2/repository)
export CLASSPATH="app.jar:$CP_STRING"

# Windows (PowerShell)
$CP_STRING = .\maven_get_deps.exe -i deps.txt -cf path --classpath --cache $HOME\.m2\repository
$env:CLASSPATH = "app.jar;" + $CP_STRING
```

### 2. Download missing Jars
The Zig tool can act as a lightweight "downloader" for your shared repository.
```powershell
maven_get_deps --input dependencies.txt --download --cache /opt/shared/lib
```
This will check `/opt/shared/lib` for each dependency in `dependencies.txt`. If a JAR is missing, it will be downloaded from Maven Central and placed in the correct Maven-compliant subdirectory.

### 3. Format Conversion
Convert a list of paths back to colon format for easier reading or comparison.
```powershell
maven_get_deps -i list_of_paths.txt -cf colon
```

### 4. Revision Management (Zero-Downtime Deployment)
The Zig tool can manage application versions by atomically swapping a "current" symlink.

```sh
# 1. Deploy a new version (updates manifest.json and prepares symlink swap)
./maven_get_deps deploy --version 1.2.4 --manifest app-manifest.json

# 2. Reconcile (ensures the 'current' symlink matches the manifest)
./maven_get_deps reconcile --manifest app-manifest.json

# 3. Generate Version Index (scans directories for version.json or custom file)
./maven_get_deps gen-index --folders folders.txt --output versions.json --version-file pkg.json
./maven_get_deps gen-index --folders folders.txt --output versions.json --version-file cp.txt
```

## Revision Management Deep Dive

### The Manifest Format (`manifest.json`)
The manifest is the source of truth for an application's deployment state.

```json
{
  "current_version": "1.2.4",
  "version_index": "versions.json",
  "trigger_cmd": "systemctl restart my-app",
  "history": [
    {
      "version": "1.2.3",
      "timestamp": 1710123456,
      "comment": "Previous stable release"
    }
  ]
}
```

- `current_version`: The ID of the version that *should* be active.
- `version_index`: Path to the index file listing physical locations of each version.
- `trigger_cmd`: (Optional) Shell command executed only when a version swap actually occurs.
- `history`: (Automatic) Log of previous deployment actions.

### Generating the Version Index (`gen-index`)
The `gen-index` command automates the creation of the `versions.json` file. It follows a strictly **non-recursive** folder scanning logic:

1.  **Direct Folder Check**: If a folder listed in your `--folders` file contains a `version.json`, it is treated as a **single version**. The tool stops scanning deeper into that specific directory.
2.  **Immediate Subfolder Check**: If the listed folder does *not* have a `version.json`, the tool scans all of its **direct children**. Each child folder is added to the index if it contains a `version.json`.

> [!NOTE]
> The tool only scans one level deep. It will **not** recursively search into sub-subdirectories.

#### The `version.json` Metadata
Each version folder should ideally contain a `version.json` (or a custom file specified via `--version-file`) to provide accurate metadata.

> [!TIP]
> **Graceful Fallbacks**: If the version file is missing **or is not a valid JSON**, the tool will automatically fall back to using the folder name for the version and the file's modification timestamp.

```json
{
  "version": "1.2.4-prod",
  "timestamp": 1710209876,
  "description": "Production build with security patches"
}
```

| Field | Description | Fallback Logic |
| :--- | :--- | :--- |
| `version` | The logical ID for deployment. | Uses the **Folder Name** if missing from JSON. |
| `timestamp`| Unix timestamp (seconds). | Uses **File Modification Time** of `version.json` if missing. |
| `description`| Optional text for the index. | Remains `null` if not provided. |

### Atomic Symlink Swaps
When `reconcile` (or `deploy`) detects a version mismatch, it:
1. Creates a temporary symlink (e.g., `current.tmp`) pointing to the new version path.
2. Uses the `rename` syscall to atomically replace the `current` symlink with the temporary one.
3. Fires the `trigger_cmd` if the swap was successful.

This prevents the application from ever seeing a "broken" link during the swap.

### Reconcile Pattern
Run `reconcile` in a **systemd path unit** or as part of a post-sync hook (e.g., after `rsync`). It will ensure the environment matches the manifest and only fires the `trigger_cmd` if a change was actually applied.

## Production Example: Java & Systemd

This example demonstrates how to use `maven-get-deps` to manage a Java service (`my-java-service`) with zero-downtime restarts and automated classpath generation.

### 1. The Systemd Service (`/etc/systemd/system/my-java-service.service`)
The service points to a `current` symlink. On every start, it uses the Zig tool to instantly generate the `CLASSPATH` from a `cp.txt` file located inside the version folder.

```ini
[Unit]
Description=My Java Service
After=network.target

[Service]
Type=simple
User=deploy
WorkingDirectory=/opt/my-java-service

# Dynamically generate CLASSPATH from the version-specific cp.txt
ExecStartPre=/bin/sh -c 'echo "CP=$(./maven-get-deps -i current/cp.txt --classpath --cache /opt/shared/m2)" > /run/my-java-service.env'

# Load the generated CP variable
EnvironmentFile=/run/my-java-service.env
ExecStart=/usr/bin/java -cp "${CP}" com.example.Main

Restart=always

[Install]
WantedBy=multi-user.target
```

### 2. The Version Folder Structure
Each version folder contains the application JAR and a `cp.txt` listing its dependencies.

```text
/opt/my-java-service/
├── v1.0.0/
│   ├── app.jar
│   └── cp.txt (contains: commons-lang3:3.12.0, etc.)
├── v1.1.0/
│   ├── app.jar
│   └── cp.txt
└── current -> v1.0.0  (Atomic symlink)
```

### 3. The Classpath Definition (`cp.txt`)
The `cp.txt` file inside each version folder defines the runtime environment. It includes the application itself and its Maven dependencies.

```text
# Local application JAR (resolved relative to cp.txt location)
./app.jar

# Maven dependencies (resolved via the --cache directory)
org.slf4j:slf4j-api:2.0.7
com.fasterxml.jackson.core:jackson-databind:2.15.2
commons-io:commons-io:2.13.0
```

> [!TIP]
> **Comments & Empty Lines**: All input files (like `cp.txt`, `deps.txt`, and `folders.txt`) support shell-style comments starting with `#` and empty lines. These will be ignored during parsing.

> [!TIP]
> **Relative Paths**: By including `./app.jar` in `cp.txt`, the Zig tool automatically resolves it to the full path of the JAR inside that specific version folder. This makes the `systemd` configuration much cleaner as it only needs to manage the `${CP}` variable.

### 4. Deployment Workflow

#### Step A: Generate or Update the Index
If you've added a new version folder, update the `versions.json` index:
```bash
./maven-get-deps gen-index --folders /opt/my-java-service --output versions.json
```

#### Step B: Deploy and Restart
Update the `manifest.json` to point to the new version. If you have configured a `trigger_cmd`, the service will restart automatically.

**`manifest.json` Configuration:**
```json
{
  "current_version": "v1.0.0",
  "version_index": "versions.json",
  "trigger_cmd": "systemctl restart my-java-service"
}
```

**Bump the version:**
```bash
# This atomically swaps the 'current' symlink and runs 'systemctl restart'
./maven_get_deps deploy --version v1.1.0 --manifest manifest.json
```

## Maven Artifact Layout

The tool relies on the standard Maven repository structure for mapping between artifact coordinates and filesystem paths.

- [Detailed Maven Artifact Layout & Regex Conversions](MAVEN_LAYOUT.md)

## Argument Reference

-   `-i, --input <file>`: Input text file containing a list of dependencies.
-   `-cf, --convert-format <format>`: Target format for output (`colon` | `path`).
-   `-cp, --classpath`: Output a single OS-separated CLASSPATH string.
-   `-c, --cache <dir>`: Base directory for paths (defaults to `~/.m2/repository`).
-   `--download`: Automatically download missing JARs from Maven Central to the cache directory.
-   `--version <v>`: (Deploy) The version ID to set as current.
-   `--manifest <file>`: Path to the `manifest.json` file (defaults to `manifest.json`).
-   `--folders <file>`: (Gen-Index) File containing list of folders to scan.
-   `-o, --output <file>`: (Gen-Index) Output index file path (defaults to `versions.json`).
-   `--version-file <name>`: (Gen-Index) Custom version file name to look for (defaults to `version.json`).

## Building from source

Ensure you have [Zig 0.15.2](https://ziglang.org/download/) installed.

```bash
zig build -Doptimize=ReleaseFast
```
The binary will be located in `zig-out/bin/`.
