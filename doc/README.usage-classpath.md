# Classpath Generation

`maven-get-deps` can generate a ready-to-use `CLASSPATH` string from your dependency list or `pom.xml`. This is useful for:
- Starting Java applications from scripts without complex classpath assembly.
- CI/CD pipelines where you want to run tests or tools without a full Maven build.
- Generating a classpath for a Docker entrypoint.

## Generating a CLASSPATH from a `pom.xml`

The Java CLI can resolve your `pom.xml` and immediately output the CLASSPATH:

```bash
# Outputs a single OS-separated classpath string to stdout
java -jar maven-get-deps-cli.jar --pom pom.xml --classpath
```

**Example Output (Linux):**
```
/home/user/.m2/repository/org/springframework/spring-core/6.1.0/spring-core-6.1.0.jar:/home/user/.m2/repository/...
```

## Transitive Resolution from a Dependencies File

The Java CLI can also ingest a simple dependencies file (in either colon or path format) and resolve all transient dependencies, effectively expanding a list of starting points into a full classpath.

```bash
# test-deps.txt
# org.slf4j:slf4j-api:2.1.0-alpha1
# com.google.guava:guava:32.1.3-jre

java -jar maven-get-deps-cli.jar test-deps.txt --classpath
```

The tool will:
1. Parse the starting artifacts from the file.
2. Build a temporary Maven model.
3. Use the Maven resolution engine to fetch all transitive dependencies.
4. Output the full resolved classpath.

## Generating a CLASSPATH from a `dependencies.txt` file

This is the primary deployment pattern. Your app ships with a pre-generated `dependencies.txt` file (in `path` format) and the tool assembles the classpath at startup:

```bash
# Generate the file during build
mvn hr.hrg:maven-get-deps:get-deps -DoutputFile=dependencies.txt

# Assemble CLASSPATH at startup (using Zig binary for speed)
CP=$(maven_get_deps -i dependencies.txt -cf path --classpath --cache /opt/shared/lib)
export CLASSPATH="myapp.jar:$CP"
exec java com.example.Main
```

## Using `--extra-classpath` for Multi-Module Projects

In Maven multi-module projects, your application typically consists of several local JARs (from each module) **plus** external library dependencies. 

Use `--extra-classpath <file>` to append additional entries to the generated CLASSPATH. The entries are read one per line from the specified file.

**Example:**
```text
# local_modules.txt
/app/core.jar
/app/api.jar
/app/service.jar
```

```bash
# Resolve external deps and merge in the local module JARs
CP=$(maven_get_deps -i web-module/dependencies.txt -cf path --classpath \
     --cache /opt/shared/lib \
     --extra-classpath local_modules.txt)

export CLASSPATH="$CP"
exec java com.example.WebMain
```

This also works with the Java CLI:
```bash
java -jar maven-get-deps-cli.jar \
  --input web-module/dependencies.txt \
  --convert-format path \
  --classpath \
  --extra-classpath local_modules.txt
```

## Dependency Format Conversion

`maven-get-deps` can convert between the two common dependency list formats:

| Format   | Example |
|----------|---------|
| **Colon** | `org.springframework:spring-core:6.1.0` |
| **Path**  | `org/springframework/spring-core/6.1.0/spring-core-6.1.0.jar` |

The `path` format maps directly to the Maven repository structure, making it ideal for building filesystem paths.

```bash
# Convert a colon-format list to path format
java -jar maven-get-deps-cli.jar --input deps-colon.txt --convert-format path

# Convert a path-format list to colon format
java -jar maven-get-deps-cli.jar --input deps-path.txt --convert-format colon
```

## CLASSPATH in Maven Plugin Mode

```bash
# Output classpath to stdout
mvn hr.hrg:maven-get-deps:get-deps -Dclasspath=true

# Save classpath to a file
mvn hr.hrg:maven-get-deps:get-deps -Dclasspath=true -DoutputFile=cp.txt
```

## Using the High-Performance Zig Binary

For ultra-fast classpath generation (e.g., in startup scripts where milliseconds matter), use the Zig binary instead of the Java CLI. It has no JVM warmup overhead.

```bash
# ~5ms vs ~300ms for the Java CLI
maven_get_deps -i dependencies.txt -cf path --classpath --cache /opt/shared/lib
```

See [README.zig.md](README.zig.md) for full details on the Zig binary.
