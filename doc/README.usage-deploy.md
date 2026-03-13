# Deployment Use Case: Lean Releases with a Shared Dependency Library

This page explains the primary design philosophy behind `maven-get-deps` and how to use it to dramatically speed up deployments.

## The Problem

To deploy a Java app to a remote server, you need your classes **and** all of your dependencies.
The usual options are:

1.  **Fat JAR**: Bundle everything into one giant file. Easy, but wasteful. A simple web app with Spring and Hibernate can have **< 1 MB** of your code dragging **50–100 MB** of dependencies. Every deployment re-uploads all 100 MB.
2.  **Flat `lib/` folder**: Copy all JARs to a `lib/` folder next to your app JAR. Better, but still copies shared libraries to each server separately. 10 microservices sharing 90% of the same libs = 10x storage.

## The Solution: A Shared Dependency Library

`maven-get-deps` enables a third approach: **a single shared dependency folder** that acts as a local Maven repository.

```
/opt/shared/lib/                    ← shared by all apps on a server or over a network mount
    com/example/my-lib/1.0/...
    org/springframework/...
    org/hibernate/...

/opt/apps/my-service/
    my-service.jar                  ← tiny (your code only, < 1 MB)
    dependencies.txt                ← the classpath manifest
    start.sh                        ← launch script
```

### The Deployment Workflow

**Step 1: Generate `dependencies.txt` during your build**

Using the Maven plugin:
```bash
mvn hr.hrg:maven-get-deps:get-deps -DoutputFile=target/dependencies.txt
```

Or using the CLI:
```bash
java -jar maven-get-deps-cli.jar --pom pom.xml --output target/dependencies.txt
```

**Step 2: Sync the dependency library to your server**

The tool can also **copy** the required JARs into a staging folder for rsync:
```bash
java -jar maven-get-deps-cli.jar --pom pom.xml --dest-dir target/lib-sync --no-copy false
rsync -avz target/lib-sync/ server:/opt/shared/lib/
```

Alternatively, manage the shared lib folder directly from CI/CD, using it as a growing pool from all deployed app versions.

**Step 3: Deploy only your thin JAR**

On the server, your deployment artifact is just:
- `my-service.jar` (~500 KB)
- `dependencies.txt` (~5 KB)
- `start.sh`

No more uploading 100 MB of libraries on every release.

---

## Launch Scripts

The tool generates the CLASSPATH string at startup from the shared library. This is fast (milliseconds with the Zig binary) and keeps the library organized by Maven artifact coordinates.

### Bash (`start.sh`):
```sh
#!/bin/sh
LIB_ROOT="/opt/shared/lib"
DEPS_FILE="dependencies.txt"

CP=$(get_deps -i "$DEPS_FILE" -cf path --classpath --cache "$LIB_ROOT")
# Or: CP=$(java -jar maven-get-deps-cli.jar --input "$DEPS_FILE" --convert-format path --classpath --cache "$LIB_ROOT")

export CLASSPATH="my-service.jar:$CP"
exec java com.example.Main "$@"
```

> **Note**: Setting `CLASSPATH` before running `java` has a bonus: the huge classpath string disappears from `ps` output on Linux, making process inspection cleaner.

### PowerShell (`start.ps1`):
```powershell
$LIB_ROOT = "C:\opt\shared\lib"
$CP = get_deps -i dependencies.txt -cf path --classpath --cache $LIB_ROOT

$env:CLASSPATH = "my-service.jar;" + $CP
java com.example.Main
```

---

## Multiple App Versions & Cleanup

If multiple versions of your application are deployed simultaneously (e.g., blue-green or canary deployments), the shared library accumulates dependencies from all versions. Each version's `dependencies.txt` acts as a manifest of which JARs it needs.

When you retire an old version, its `dependencies.txt` can be used to identify and remove JARs that are no longer needed by any current version:

```bash
# (Future: diff dependencies between versions to find obsolete JARs)
```

---

## Container Deployments

For Docker and Kubernetes deployments, see these guides:

- **[Dynamic Cache (Docker)](README.docker.md)** — Use the Zig tool inside Docker to fill a shared Maven cache at container startup.
- **[Static Classpath (Docker & K8s)](README.static-docker.md)** — Bake a fixed classpath into your Docker image at build time for the leanest, most secure runtime.
- **[Systemd Deployment Guide](README.systemd.md)** — Step-by-step example of deploying a multi-module Java daemon.
