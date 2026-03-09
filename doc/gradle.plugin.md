# Gradle Plugin for maven-get-deps

To keep `maven-get-deps` clean and avoid polluting the underlying project with Gradle wrappers and dependencies, you can create a lightweight, standalone Gradle plugin or task in a separate project.

This guide explains how to build a minimal Gradle equivalent that achieves the core features of `maven-get-deps` for a Gradle environment:
1. Resolving project dependencies and exporting them to a file (like `deps.txt`).
2. Supporting an `--extra-classpath` option to append custom entries directly into the generated file.

---

## 1. Quick Ad-Hoc Script (`build.gradle.kts`)

If you want to quickly generate the dependencies list without building a full standalone plugin, you can register a custom `getDeps` task directly in your Gradle build scripts.

```kotlin
// build.gradle.kts
tasks.register("getDeps") {
    group = "build"
    description = "Exports runtime dependencies to a file, mimicking maven-get-deps."
    
    // Optional parameters: read extra classpath file and exclusion filters via properties
    val extraClasspathFile = project.findProperty("extraClasspath") as String?
    val excludeClasspathFilter = project.findProperty("excludeClasspath") as String?
    
    doLast {
        val destFile = layout.buildDirectory.file("deps.txt").get().asFile
        // Resolve the runtime classpath for the main source set
        val runtimeClasspath = configurations.getByName("runtimeClasspath")
        
        val deps = mutableListOf<String>()
        val excludeFilters = excludeClasspathFilter?.split(",")?.map { it.trim() } ?: emptyList()

        // Helper function to check if an item should be excluded
        fun shouldExclude(item: String, artifactId: String? = null): Boolean {
            return excludeFilters.any { filter ->
                item.contains(filter) || (artifactId != null && artifactId.contains(filter))
            }
        }
        
        // 1. Add resolved dependencies as absolute paths
        runtimeClasspath.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            val artifactPath = artifact.file.absolutePath
            val artifactId = "${artifact.moduleVersion.group}:${artifact.moduleVersion.name}"
            
            if (!shouldExclude(artifactPath, artifactId)) {
                deps.add(artifactPath)
            }
        }
        
        // 2. Add extra classpath entries if the parameter was provided
        if (extraClasspathFile != null) {
            val extraFile = file(extraClasspathFile)
            if (extraFile.exists()) {
                extraFile.readLines().forEach { line ->
                    if (line.isNotBlank()) {
                        if (!shouldExclude(line.trim())) {
                            deps.add(line.trim())
                        }
                    }
                }
            } else {
                logger.warn("Extra classpath file not found: $extraClasspathFile")
            }
        }
        
        // 3. Write output to deps.txt
        destFile.parentFile.mkdirs()
        destFile.writeText(deps.joinToString("\n"))
        println("Generated dependencies list at: ${destFile.absolutePath}")
    }
}
```

### Usage Examples

Generate `deps.txt` using the resolved dependencies from your project:
```bash
./gradlew getDeps
```

Generate `deps.txt` and append extra entries from `extra-cp.txt`:
```bash
./gradlew getDeps -PextraClasspath=extra-cp.txt
```

Generate `deps.txt` and exclude specific artifacts (by ID or path substring):
```bash
./gradlew getDeps \
    -PextraClasspath=extra-cp.txt \
    -PexcludeClasspath=com.google.guava:guava,slf4j-simple-1.7.36.jar
```

---

## 2. Bootstrapping a Standalone Gradle Plugin

If you need this functionality across multiple projects and want to keep their `build.gradle` files clean, the best approach is to extract the logic into a standalone Gradle plugin project.

1. **Initialize the Plugin Project**
   Create a new directory (separate from `maven-get-deps`) and generate a Gradle plugin project:
   ```bash
   mkdir maven-get-deps-gradle-plugin
   cd maven-get-deps-gradle-plugin
   gradle init --type java-gradle-plugin
   ```

2. **Implement the Plugin Logic**
   Write a custom Task class in Java or Kotlin that defines `@InputFiles extraClasspath` and resolves `project.getConfigurations().getByName("runtimeClasspath")`.
   
3. **Publish Locally**
   Publish the plugin to your `mavenLocal()` (`~/.m2/repository`) using the `maven-publish` plugin so that your other Gradle projects can consume it easily.

## Why keep this separate?
By managing the Gradle integration as a separate project:
- `maven-get-deps` remains a focused, fast, CLI/Maven-driven utility.
- We avoid pulling `gradle-api` onto the classpath of this project, sidestepping version conflicts and large dependency downloads.
- Our deployment philosophy stays lightweight.
