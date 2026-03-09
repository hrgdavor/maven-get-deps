# maven-get-deps Gradle Plugin

A lightweight Gradle plugin that provides functionality similar to the `maven-get-deps` CLI, allowing you to export your project's runtime dependencies to a flat file.

## Features

- Resolves and exports absolute paths of all runtime dependencies.
- Supports adding extra classpath entries from an external file.
- Supports excluding artifacts by ID or path substring.
- Works with both Groovy and Kotlin DSL.

## Installation

Since the plugin is not yet published to the Gradle Plugin Portal, you must install it to your local Maven repository:

```bash
# In the plugin directory
./gradlew publishToMavenLocal
```

### Apply the Plugin

In your target project's `settings.gradle` (Groovy) or `settings.gradle.kts` (Kotlin), ensure `mavenLocal()` is included in the plugin management:

#### Kotlin DSL (`settings.gradle.kts`)
```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

#### Groovy DSL (`settings.gradle`)
```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

Then, apply the plugin in your `build.gradle(.kts)`:

#### Kotlin DSL (`build.gradle.kts`)
```kotlin
plugins {
    id("hr.hrg.maven.getdeps") version "1.0.0-SNAPSHOT"
}
```

#### Groovy DSL (`build.gradle`)
```groovy
plugins {
    id "hr.hrg.maven.getdeps" version "1.0.0-SNAPSHOT"
}
```

## Usage

The plugin registers a task named `getDeps`. By default, it writes the dependency list to `build/deps.txt`.

### Configuration

You can customize the task behavior in your build script:

#### Kotlin DSL
```kotlin
tasks.named<hr.hrg.maven.getdeps.gradle.GetDepsTask>("getDeps") {
    outputFile.set(file("custom-deps.txt"))
    extraClasspath.set("extra-cp.txt")
    excludeClasspath.set("com.google.guava:guava,slf4j-simple-1.7.36.jar")
}
```

#### Groovy DSL
```groovy
getDeps {
    outputFile = file("custom-deps.txt")
    extraClasspath = "extra-cp.txt"
    excludeClasspath = "com.google.guava:guava,slf4j-simple-1.7.36.jar"
}
```

### Command Line Options

You can also provide configuration via project properties (`-P`):

- `extraClasspath`: Path to a file containing extra classpath entries.
- `excludeClasspath`: Comma-separated list of artifact IDs or filename patterns to exclude.

Example:
```bash
./gradlew getDeps -PextraClasspath=extra-cp.txt -PexcludeClasspath=com.google.guava:guava
```

## How it works

The plugin resolves the `runtimeClasspath` configuration, extracts the absolute paths of all resolved artifacts, and merges them with any entries from the `extraClasspath` file, while filtering out any matches found in `excludeClasspath`.
