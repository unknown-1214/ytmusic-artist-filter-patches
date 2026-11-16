group = "app.template"

patches {
    about {
        name = "YTM Artist Filter"
        description = "YouTube Music: keep auto mixes to the artists you allow"
        source = "https://github.com/unknown-1214/ytmusic-artist-filter-patches"
        author = "unknown-1214"
        contact = "https://github.com/unknown-1214/ytmusic-artist-filter-patches/issues"
        website = "https://github.com/unknown-1214/ytmusic-artist-filter-patches"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
