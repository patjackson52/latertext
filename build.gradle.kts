plugins {
    base
}

tasks.register("jvmUnitTest") {
    group = "verification"
    description = "Runs unit tests in every pure JVM module."
    dependsOn(
        ":core:model:test",
        ":core:domain:test",
        ":data:api:test",
        ":platform:api:test",
        ":testing:test",
    )
}
