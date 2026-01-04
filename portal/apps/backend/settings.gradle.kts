plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "phoenix-portal-backend"

// Include the shared module from the main project
includeBuild("../../../") {
    dependencySubstitution {
        substitute(module("com.devil.phoenixproject:shared")).using(project(":shared"))
    }
}
