plugins {
    id("java")
    id("hr.hrg.maven.getdeps")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.guava:guava:31.1-jre")
}
