plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "hr.hrg"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("mavenGetDepsPlugin") {
            id = "hr.hrg.maven.getdeps"
            implementationClass = "hr.hrg.maven.getdeps.gradle.MavenGetDepsPlugin"
        }
    }
}
