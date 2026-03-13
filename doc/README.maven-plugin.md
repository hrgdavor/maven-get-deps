# maven-get-deps Maven Plugin

The `maven-get-deps` plugin allows you to integrate dependency resolution directly into your Maven build lifecycle.

## 🚀 Usage

Run the plugin directly from the terminal to resolve or copy dependencies:

```bash
mvn hr.hrg:maven-get-deps:1.0.0:get-deps -DdestDir=lib -DcopyJars=true -DoutputFile=deps.txt
```

## ⚙️ Configuration Parameters

| Parameter | Description |
|---|---|
| `destDir` | Directory for listing/copying artifacts |
| `copyJars` | Copy JARs to `destDir` (default: `false`) |
| `outputFile` | Save dependency list to file |
| `scopes` | Scopes to include (default: `compile,runtime`) |
| `reportFile` | Markdown dependency-size report |
| `classpath` | Output as OS-separated `CLASSPATH` string (default: `false`) |
| `cache` | Override local Maven repository path |

## 📖 Features

- **Direct POM Integration**: Inherits project context and profiles.
- **Transitive Resolution**: Uses the standard Maven Aether/Artifact resolution engine.
- **Deployment Preparation**: Easily gather dependencies for thin JAR deployments during the `package` phase.
