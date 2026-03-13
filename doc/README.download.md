# Download Guide: `maven-get-deps` Tools

This guide provides a central location for downloading project artifacts and instructions for setting them up on your system.

## 1. Tool Overview

| Tool | Language | Primary Purpose | Examples | Docs |
|---|---|---|---|---|
| **maven_get_deps** | Java | **Transitive resolution**: Full-featured expansion, downloads, and size reporting. | [View](#java-maven_get_deps) | [Docs](README.maven_get_deps.md) |
| **cve12** | Java | **CVE Scanning**: Focused offline scanner using OWASP v12. | [View](#java-cve12) | [Docs](README.usage-cve.md) |
| **get_deps** | Zig | **Ultra-fast Path Resolution**: Recommended for production runtimes. | [View](#zig-get_deps) | [Docs](README.get_deps.md) |
| **version_manager** | Zig | **Deployment**: Zero-downtime atomic symlink swaps. | [View](#zig-version_manager) | [Docs](README.version_manager.md) |
| **gen_index** | Zig | **Metadata**: Generate deployment index files. | [View](#zig-gen_index) | [Docs](README.gen_index.md) |

---

## 2. Latest Releases

Download artifacts from the [GitHub Releases Page](https://github.com/hrgdavor/maven-get-deps/releases/latest).

### Java Implementation (Native & JAR)
- **Linux (x64)**: `maven-get-deps-linux-x64.tar.gz`, `cve12-linux-x64.tar.gz`
- **Windows (x64)**: `maven-get-deps-windows-x64.zip`, `cve12-windows-x64.zip`
- **Platform Independent**: `maven-get-deps-cli.jar`, `cve12-cli.jar`

### Zig Implementation (Native Binaries)
- **Linux (x64)**: `get-deps-zig-linux-x64.tar.gz`, `version-manager-zig-linux-x64.tar.gz`, `gen-index-zig-linux-x64.tar.gz`
- **Linux (ARM64)**: `get-deps-zig-linux-arm64.tar.gz`, `version-manager-zig-linux-arm64.tar.gz`, `gen-index-zig-linux-arm64.tar.gz`
- **Windows (x64)**: `get-deps-zig-windows-x64.zip`, `version-manager-zig-windows-x64.zip`, `gen-index-zig-windows-x64.zip`

---

## 3. Download & Extract Examples

### <a name="java-maven_get_deps"></a> Java Reference Tool (`maven_get_deps`)

Full transitive expansion and reporting.

- **Linux (x64)**
  ```bash
  curl -L -O https://github.com/hrgdavor/maven-get-deps/releases/latest/download/maven-get-deps-linux-x64.tar.gz
  tar -xzf maven-get-deps-linux-x64.tar.gz
  ./maven-get-deps --version
  ```
- **Windows (x64)**
  ```powershell
  Invoke-WebRequest -Uri "https://github.com/hrgdavor/maven-get-deps/releases/latest/download/maven-get-deps-windows-x64.zip" -OutFile "maven-get-deps.zip"
  Expand-Archive -Path "maven-get-deps.zip" -DestinationPath "."
  .\maven-get-deps.exe --version
  ```
- **CLI JAR (Generic)**
  ```bash
  curl -L -O https://github.com/hrgdavor/maven-get-deps/releases/latest/download/maven-get-deps-cli.jar
  java -jar maven-get-deps-cli.jar --version
  ```

---

### <a name="java-cve12"></a> CVE Scanner (`cve12`)

Focused vulnerability scanner using OWASP v12.

- **Linux (x64)**
  ```bash
  curl -L -O https://github.com/hrgdavor/maven-get-deps/releases/latest/download/cve12-linux-x64.tar.gz
  tar -xzf cve12-linux-x64.tar.gz
  ./cve12 --help
  ```
- **Windows (x64)**
  ```powershell
  Invoke-WebRequest -Uri "https://github.com/hrgdavor/maven-get-deps/releases/latest/download/cve12-windows-x64.zip" -OutFile "cve12.zip"
  Expand-Archive -Path "cve12.zip" -DestinationPath "."
  .\cve12.exe --help
  ```
- **CLI JAR (Generic)**
  ```bash
  curl -L -O https://github.com/hrgdavor/maven-get-deps/releases/latest/download/cve12-cli.jar
  java -jar cve12-cli.jar --help
  ```

---

### <a name="zig-get_deps"></a> Zig Dependency Resolver (`get_deps`)

Ultra-fast path resolution and Maven cache filler.

- **Linux (x64)**: `get-deps-zig-linux-x64.tar.gz`
- **Linux (ARM64)**: `get-deps-zig-linux-arm64.tar.gz`
- **Windows (x64)**: `get-deps-zig-windows-x64.zip`
- **Setup (Example)**:
  ```bash
  curl -L -O https://github.com/hrgdavor/maven-get-deps/releases/latest/download/get-deps-zig-linux-x64.tar.gz
  tar -xzf get-deps-zig-linux-x64.tar.gz
  ./get_deps --version
  ```

---

### <a name="zig-version_manager"></a> Zig Version Manager (`version_manager`)

Atomic symlink deployments.

- **Artifacts**: `version-manager-zig-linux-x64.tar.gz`, `version-manager-zig-linux-arm64.tar.gz`, `version-manager-zig-windows-x64.zip`
- **Setup (Example)**:
  ```bash
  curl -L -O https://github.com/hrgdavor/maven-get-deps/releases/latest/download/version-manager-zig-linux-x64.tar.gz
  tar -xzf version-manager-zig-linux-x64.tar.gz
  ./version_manager --help
  ```

---

### <a name="zig-gen_index"></a> Zig Index Generator (`gen_index`)

Generate deployment metadata.

- **Artifacts**: `gen-index-zig-linux-x64.tar.gz`, `gen-index-zig-linux-arm64.tar.gz`, `gen-index-zig-windows-x64.zip`
- **Setup (Example)**:
  ```bash
  curl -L -O https://github.com/hrgdavor/maven-get-deps/releases/latest/download/gen-index-zig-linux-x64.tar.gz
  tar -xzf gen-index-zig-linux-x64.tar.gz
  ./gen_index --help
  ```
