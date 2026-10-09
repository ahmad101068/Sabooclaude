plugins {
    `java-library`
    kotlin("jvm")
}
dependencies {
    api(project(":modules:sales"))
    api(project(":modules:purchasing"))
    api(project(":modules:payroll"))
    api(project(":modules:assets"))
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}
