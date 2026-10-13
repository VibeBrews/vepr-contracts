plugins {
    `java-library`
}

dependencies {
    implementation(libs.bcprov)
    implementation(libs.bcpkix)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

tasks.test {
    useJUnitPlatform()
}

val vectorsDir = rootProject.projectDir.resolve("../signing/vectors")

val javaToolchains = project.extensions.getByType<JavaToolchainService>()
val toolchainLauncher = javaToolchains.launcherFor(
    project.extensions.getByType<JavaPluginExtension>().toolchain
)

val generateVectors by tasks.registering(JavaExec::class) {
    group = "signing"
    description = "Generate signing test vectors (signing/vectors/v1.json)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.vepr.contracts.signing.VectorGenerator")
    javaLauncher.set(toolchainLauncher)
    args(vectorsDir.absolutePath)
}

val verifyVectorsUpToDate by tasks.registering(JavaExec::class) {
    group = "signing"
    description = "Verify that committed signing/vectors/v1.json matches regenerated vectors"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.vepr.contracts.signing.VectorGenerator")
    javaLauncher.set(toolchainLauncher)
    args("--verify", vectorsDir.absolutePath)
}

tasks.named("check") {
    dependsOn(verifyVectorsUpToDate)
}
