# maven-get-deps Maven Plugin

The `maven-get-deps` plugin allows you to integrate dependency resolution directly into your Maven build lifecycle. It is highly optimized for creating thin deployments by resolving transitive dependencies and preparing an application's classpath.

## 🚀 Usage

Run the plugin directly from the terminal to resolve or copy dependencies:

```bash
mvn hr.hrg:maven-get-deps-maven-plugin:1.0.4:get-deps -DdestDir=lib -DcopyJars=true -DoutputFile=deps.txt
```

### Install or deploy the Mojo

If you are building the plugin locally, install it into your local Maven repository first:

```bash
mvn install -pl maven-get-deps-maven-plugin
```

To publish the plugin to a remote repository such as Maven Central, deploy the Maven plugin artifact from the plugin module:

```bash
mvn deploy -pl maven-get-deps-maven-plugin
```
> Note: Maven Central is not a writable deployment endpoint. You must publish through Sonatype OSSRH staging using the correct deploy URL, not `https://central.sonatype.com/`.
>
> Example with OSSRH staging:
>
> ```bash
> mvn deploy -pl maven-get-deps-maven-plugin \
>   -DaltDeploymentRepository=ossrh::default::https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/
> ```
>
> Also make sure your `settings.xml` contains matching credentials for the server id `ossrh`.
This makes the plugin available by its coordinates `hr.hrg:maven-get-deps-maven-plugin:1.0.4`.

### Generating a CLASSPATH string

Generate a single-line string of absolute paths suitable for the `CLASSPATH` environment variable:

```bash
mvn hr.hrg:maven-get-deps-maven-plugin:1.0.4:get-deps -Dclasspath=true -DoutputFile=cp.txt
```

Uses the **system-dependent path separator** (`;` on Windows, `:` on Linux/macOS).

### Dependency size report

Write a Markdown dependency size report for the resolved artifacts:

```bash
mvn hr.hrg:maven-get-deps-maven-plugin:1.0.4:get-deps -DreportFile=target/deps-report.md
```

Example `deps-report.md` output:

```md
| Size (KB) | Dependency | File |
|----------:|:-----------|:-----|
|  215 | hr.hrg:maven-get-deps-api:1.0.4 | /path/to/.m2/repository/hr/hrg/maven-get-deps-api/1.0.4/maven-get-deps-api-1.0.4.jar |
|  430 | hr.hrg:maven-get-deps-maven:1.0.4 | /path/to/.m2/repository/hr/hrg/maven-get-deps-maven/1.0.4/maven-get-deps-maven-1.0.4.jar |

> Total size: 645432 bytes
```

### Excluding sibling / reactor modules

Use `excludeGroupIds` to strip out artifacts from the same multi-module project:

```bash
mvn hr.hrg:maven-get-deps-maveč-1n-plugin:1.0.4:get-deps -DoutputFile=target/deps.txt -DexcludeGroupIds=hr.hrg
```

Or exclude specific artifacts by id:

```bash
mvn hr.hrg:maven-get-deps-maven-plugin:1.0.4::get-deps -DoutputFile=target/deps.txt -DexcludeArtifactIds=commons-cli,slf4j-simple
```

Both parameters also work in POM configuration:

```xml
<configuration>
  <outputFile>${project.build.directory}/deps.txt</outputFile>
  <excludeGroupIds>hr.hrg</excludeGroupIds>
  <excludeArtifactIds>slf4j-simple</excludeArtifactIds>
</configuration>
```

## ⚙️ Configuration Parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `outputFile` | `-DoutputFile` | *(print to log)* | Write output to this file instead of the build log. |
| `reportFile` | `-DreportFile` | — | Write a Markdown dependency size report to this file. |
| `classpath` | `-Dclasspath` | `false` | When `true`, output is a single-line OS path-separator string (absolute paths). When `false`, output is `groupId:artifactId:version` per line. |
| `scopes` | `-Dscopes` | `compile,runtime` | Comma-separated list of dependency scopes to include. |
| `destDir` | `-DdestDir` | — | Target directory. Required when `copyJars=true`. |
| `copyJars` | `-DcopyJars` | `false` | Copy resolved JARs into `destDir`. Output paths will point to the copied files. |
| `excludeGroupIds` | `-DexcludeGroupIds` | — | Comma-separated groupIds to exclude (e.g. `hr.hrg,com.example`). Useful for filtering out sibling/reactor modules. |
| `excludeArtifactIds` | `-DexcludeArtifactIds` | — | Comma-separated artifactIds to exclude. |

## 📖 Features

- **Direct POM Integration**: Inherits project context, profiles, and exclusions from your `pom.xml`.
- **Transitive Resolution**: Uses the standard Maven Aether/Artifact resolution engine for 100% compatibility.
- **OS-Aware Classpath**: Automatically detects whether to use `;` or `:` as a separator.
- **Deployment Preparation**: Easily gather dependencies for thin JAR deployments during the `package` phase.
