sourceSets {
    main {
        resources {
            srcDir("${rootProject.projectDir}/..")
            include("openapi/**")
        }
    }
}
