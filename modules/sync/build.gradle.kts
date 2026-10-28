plugins {
    `java-library`
    kotlin("jvm")
}
dependencies {
    api(project(":modules:core"))
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // The hub tests open the shared test database, which is PostgreSQL in the server-path job (ADR-0060).
    testRuntimeOnly("org.postgresql:postgresql:42.7.4")
}
