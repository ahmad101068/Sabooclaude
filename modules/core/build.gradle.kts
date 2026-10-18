plugins {
    `java-library`
    kotlin("jvm")
}
dependencies {
    api(project(":modules:persistence"))
    api(project(":modules:posimport"))
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testRuntimeOnly("org.postgresql:postgresql:42.7.4")
}
