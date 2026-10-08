plugins {
    `java-library`
    kotlin("jvm")
}
dependencies {
    api(project(":modules:persistence"))
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}
