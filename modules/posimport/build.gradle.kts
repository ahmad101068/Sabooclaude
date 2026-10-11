plugins {
    `java-library`
    kotlin("jvm")
}
// Reading point-of-sale exports (Excel/CSV): pure JVM, no spreadsheet library (ADR-0026).
dependencies { api(project(":modules:kernel")) }
