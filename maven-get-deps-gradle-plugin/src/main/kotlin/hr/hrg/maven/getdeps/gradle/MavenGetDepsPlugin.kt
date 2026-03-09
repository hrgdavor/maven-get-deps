package hr.hrg.maven.getdeps.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class GetDepsTask : DefaultTask() {

    @get:Input
    @get:Optional
    abstract val extraClasspath: Property<String>

    @get:Input
    @get:Optional
    abstract val excludeClasspath: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        description = "Exports runtime dependencies to a file, mimicking maven-get-deps."
        group = "build"
        outputFile.convention(project.layout.buildDirectory.file("deps.txt"))
    }

    @TaskAction
    fun action() {
        val destFile = outputFile.get().asFile
        val deps = mutableListOf<String>()

        val excludes = normalizeExcludes(excludeClasspath.orNull)

        // 1. Add resolved dependencies as absolute paths
        val runtimeClasspath = project.configurations.findByName("runtimeClasspath")
        if (runtimeClasspath != null) {
            runtimeClasspath.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                val group = artifact.moduleVersion.id.group
                val name = artifact.moduleVersion.id.name
                val path = artifact.file.absolutePath

                if (!isExcluded(group, name, path, excludes)) {
                    deps.add(path)
                }
            }
            project.logger.lifecycle("Resolved ${deps.size} dependencies from runtimeClasspath.")
        } else {
            project.logger.warn("No 'runtimeClasspath' configuration found. Make sure the Java or Kotlin plugin is applied.")
        }

        // 2. Add extra classpath entries if provided
        if (extraClasspath.isPresent) {
            val extraFilePath = extraClasspath.get()
            val extraFile = project.file(extraFilePath)
            if (extraFile.exists()) {
                var count = 0
                extraFile.readLines().forEach { line ->
                    if (line.isNotBlank()) {
                        deps.add(line.trim())
                        count++
                    }
                }
                project.logger.lifecycle("Appended $count extra entries from $extraFilePath.")
            } else {
                project.logger.warn("Extra classpath file not found: $extraFilePath")
            }
        }

        // 3. Write output to deps.txt
        destFile.parentFile.mkdirs()
        destFile.writeText(deps.joinToString("\n"))
        project.logger.lifecycle("Generated dependencies list at: ${destFile.absolutePath}")
    }

    private fun normalizeExcludes(excludes: String?): Set<String> {
        val result = mutableSetOf<String>()
        if (excludes == null || excludes.isBlank()) return result
        excludes.split(",").forEach { s ->
            val trimmed = s.trim().replace('\\', '/')
            if (trimmed.isNotBlank()) {
                result.add(trimmed)
                // If it's a repository path-like, try to extract GA
                if (trimmed.contains("/") && trimmed.endsWith(".jar")) {
                    extractGAFromPath(trimmed)?.let { result.add(it) }
                }
            }
        }
        return result
    }

    private fun extractGAFromPath(path: String): String? {
        val parts = path.split("/")
        if (parts.size < 4) return null
        val artifactId = parts[parts.size - 3]
        val groupId = parts.subList(0, parts.size - 3).joinToString(".")
        return "$groupId:$artifactId"
    }

    private fun isExcluded(groupId: String, artifactId: String, absolutePath: String, excludes: Set<String>): Boolean {
        if (excludes.isEmpty()) return false
        if (excludes.contains("$groupId:$artifactId")) return true
        
        val normalizedPath = absolutePath.replace('\\', '/')
        if (excludes.contains(normalizedPath)) return true
        
        // Check if any exclude is a suffix (for relative paths) or a substring of the absolute path
        excludes.forEach { ex ->
            if (normalizedPath.endsWith(ex)) return true
        }
        
        return false
    }
}

class MavenGetDepsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("getDeps", GetDepsTask::class.java) {
            // Read extraClasspath and excludeClasspath from project properties if available using a provider
            extraClasspath.set(project.providers.gradleProperty("extraClasspath").filter { it.isNotBlank() })
            excludeClasspath.set(project.providers.gradleProperty("excludeClasspath").filter { it.isNotBlank() })
        }
    }
}
