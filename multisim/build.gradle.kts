plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("dagger.hilt.android.plugin")
    id("com.google.devtools.ksp")
    id("maven-publish")
    id("signing")
    kotlin("kapt")
}

android {

    namespace = "com.hover.multisim"

    compileSdk = 33

    defaultConfig {
        minSdk = 18
        targetSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    kapt {
        correctErrorTypes = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    dependencies {
        implementation(libs.android.appcompat)

        implementation(libs.android.localbroadcastmanager)

        implementation(libs.android.work)

        implementation(libs.sentry)

        implementation(libs.bundles.hilt)

        implementation(libs.bundles.room)
        ksp(libs.room.compiler)

        testImplementation(libs.test.junit4)
        testImplementation(libs.kotlin.coroutines.test)
        testImplementation(libs.test.robolectric)
        testImplementation(libs.test.mockk)
        testImplementation(libs.test.fixture)
    }
}

kotlin {
    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }
    }
}

tasks {
    val sourceFiles = android.sourceSets.getByName("main").java.srcDirs

    register<Javadoc>("withJavadoc") {
        isFailOnError = false
        dependsOn(android.libraryVariants.toList().last().javaCompileProvider)

        if (! project.plugins.hasPlugin("org.jetbrains.kotlin.android")) {
            setSource(sourceFiles)
        }
        android.bootClasspath.forEach { classpath += project.fileTree(it) }
        android.libraryVariants.forEach { variant ->
            variant.javaCompileProvider.get().classpath.files.forEach { file ->
                classpath += project.fileTree(file)
            }
        }
        exclude("**/internal/*")
        val options = options as StandardJavadocDocletOptions
        options.links("https://developer.android.com/reference")
        options.links("https://docs.oracle.com/javase/8/docs/api/")

        // Workaround for the following error when running on on JDK 9+
        // "The code being documented uses modules but the packages defined in ... are in the unnamed module."
        if (JavaVersion.current() >= JavaVersion.VERSION_1_9) {
            options.addStringOption("-release", "8")
        }
    }

    register<Jar>("withJavadocJar") {
        archiveClassifier.set("javadoc")
        dependsOn(named("withJavadoc"))
        val destination = named<Javadoc>("withJavadoc").get().destinationDir
        from(destination)
    }

    register<Jar>("withSourcesJar") {
        archiveClassifier.set("sources")
        from(sourceFiles)
    }
}

afterEvaluate {
    fun Project.get(name: String, def: String = "$name not found") =
        properties[name]?.toString() ?: System.getenv(name) ?: def

    fun Project.getRepositoryUrl(): java.net.URI {
        val isReleaseBuild = !get("POM_VERSION_NAME").contains("SNAPSHOT")
        val releaseRepoUrl = get(
            "RELEASE_REPOSITORY_URL",
            "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
        )
        val snapshotRepoUrl = get(
            "SNAPSHOT_REPOSITORY_URL",
            "https://s01.oss.sonatype.org/content/repositories/snapshots/"
        )
        return uri(if (isReleaseBuild) releaseRepoUrl else snapshotRepoUrl)
    }

    publishing {
        publications {
            val props = project.properties
            repositories {
                maven {
                    url = getRepositoryUrl()
                    credentials {
                        username = project.get("ossUsername")
                        password = project.get("ossPassword")
                    }
                }
            }
            val publicationName = props["POM_NAME"]?.toString() ?: "publication"
            create<MavenPublication>(publicationName) {
                from(project.components["release"])
                artifact(tasks.named<Jar>("withJavadocJar"))
                artifact(tasks.named<Jar>("withSourcesJar"))

                pom {
                    groupId = project.get("GROUP")
                    artifactId = project.get("POM_ARTIFACT_ID")
                    version = project.get("VERSION_NAME")

                    name.set(project.get("POM_NAME"))
                    description.set(project.get("POM_DESCRIPTION"))
                    url.set(project.get("POM_URL"))
                    packaging = project.get("POM_PACKAGING")

                    scm {
                        url.set(project.get("POM_SCM_URL"))
                        connection.set(project.get("POM_SCM_CONNECTION"))
                        developerConnection.set(project.get("POM_SCM_DEV_CONNECTION"))
                    }

                    organization {
                        name.set(project.get("POM_DEVELOPER_NAME"))
                        url.set(project.get("POM_DEVELOPER_URL"))
                    }

                    developers {
                        developer {
                            id.set(project.get("POM_DEVELOPER_ID"))
                            name.set(project.get("POM_DEVELOPER_NAME"))
                        }
                    }

                    licenses {
                        license {
                            name.set(project.get("POM_LICENCE_NAME"))
                            url.set(project.get("POM_LICENCE_URL"))
                            distribution.set(project.get("POM_LICENCE_DIST"))
                        }
                    }
                }
            }

            signing {
                val signingKeyId = project.get("signingKeyId")
                val signingKeyPassword = project.get("signingKeyPassword")
                val signingKey = project.get("signingKey")
                useInMemoryPgpKeys(signingKeyId, signingKey, signingKeyPassword)
                sign(publishing.publications.getByName(publicationName))
            }
        }
    }
}
