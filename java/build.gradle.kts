import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.protobuf) apply false
}

group = "io.vepr"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    group = "io.vepr"

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        withSourcesJar()
    }

    val artifactBaseName = project.name.replace("contracts-", "vepr-contracts-")

    configure<org.gradle.api.plugins.BasePluginExtension> {
        archivesName.set(artifactBaseName)
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifactId = artifactBaseName
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri(
                    "https://maven.pkg.github.com/${
                        providers.environmentVariable("GITHUB_REPOSITORY").getOrElse("vepr-io/vepr-contracts")
                    }"
                )
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").getOrElse("")
                    password = providers.environmentVariable("GITHUB_TOKEN").getOrElse("")
                }
            }
            mavenLocal()
        }
    }
}
