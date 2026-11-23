package com.gravity.ide.workspace

import java.io.File

/**
 * Real starter projects. The Android template is a complete Gradle project that builds
 * with `./gradlew assembleDebug` wherever an Android toolchain is available (desktop, CI,
 * or a properly provisioned on-device toolchain).
 */
enum class ProjectTemplate(
    val label: String,
    val type: String,
    val buildCommand: String?,
    val artifactPattern: String?,
    val testCommand: String?,
) {
    EMPTY("Empty project", "GENERIC", null, null, null),
    WEB_APP("Phone app (App Builder)", "APP", null, null, null),
    ANDROID_COMPOSE("Android app (Kotlin + Compose)", "ANDROID", "./gradlew assembleDebug --offline", "app/build/outputs/apk/debug/*.apk", "./gradlew testDebugUnitTest --offline"),
    WEB_STATIC("Static website (HTML/CSS/JS)", "WEB", null, "dist/index.html", null),
    WEB_GAME("HTML5 canvas game", "GAME", null, "dist/index.html", null),
    KOTLIN_CONSOLE("Kotlin console app", "KOTLIN", "kotlinc src/Main.kt -include-runtime -d build/app.jar", "build/app.jar", null),
    PYTHON("Python script", "PYTHON", null, null, "python3 -m unittest"),
    SHELL("Shell scripts", "SHELL", "sh build.sh", null, "sh test.sh");

    fun write(root: File, name: String) {
        val pkg = "com.example." + name.lowercase().replace(Regex("[^a-z0-9]"), "").ifEmpty { "app" }.let { if (it[0].isDigit()) "app$it" else it }
        fun put(path: String, content: String) {
            val f = File(root, path)
            f.parentFile?.mkdirs()
            f.writeText(content.trimIndent() + "\n")
        }
        put("GRAVITY.md", """
            # $name

            Project instructions for Gravity's agents. Describe architecture decisions,
            coding conventions and anything an AI collaborator must know. Agents read
            this file before every task; you can edit it at any time.
        """)
        when (this) {
            EMPTY -> put("README.md", "# $name")
            ANDROID_COMPOSE -> {
                put("settings.gradle.kts", """
                    pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
                    dependencyResolutionManagement { repositories { google(); mavenCentral() } }
                    rootProject.name = "$name"
                    include(":app")
                """)
                put("build.gradle.kts", """
                    plugins {
                        id("com.android.application") version "8.13.0" apply false
                        id("org.jetbrains.kotlin.android") version "2.2.21" apply false
                        id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
                    }
                """)
                put("gradle.properties", """
                    android.useAndroidX=true
                    org.gradle.jvmargs=-Xmx2g
                """)
                put("app/build.gradle.kts", """
                    plugins {
                        id("com.android.application")
                        id("org.jetbrains.kotlin.android")
                        id("org.jetbrains.kotlin.plugin.compose")
                    }
                    android {
                        namespace = "$pkg"
                        compileSdk = 36
                        defaultConfig {
                            applicationId = "$pkg"
                            minSdk = 26
                            targetSdk = 35
                            versionCode = 1
                            versionName = "1.0"
                        }
                        buildFeatures { compose = true }
                        compileOptions {
                            sourceCompatibility = JavaVersion.VERSION_17
                            targetCompatibility = JavaVersion.VERSION_17
                        }
                    }
                    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
                    dependencies {
                        implementation(platform("androidx.compose:compose-bom:2025.08.00"))
                        implementation("androidx.activity:activity-compose:1.10.1")
                        implementation("androidx.compose.material3:material3")
                        implementation("androidx.compose.ui:ui")
                        testImplementation("junit:junit:4.13.2")
                    }
                """)
                put("app/src/main/AndroidManifest.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <application android:label="$name" android:theme="@android:style/Theme.Material.NoActionBar">
                            <activity android:name=".MainActivity" android:exported="true">
                                <intent-filter>
                                    <action android:name="android.intent.action.MAIN" />
                                    <category android:name="android.intent.category.LAUNCHER" />
                                </intent-filter>
                            </activity>
                        </application>
                    </manifest>
                """)
                put("app/src/main/java/${pkg.replace('.', '/')}/MainActivity.kt", """
                    package $pkg

                    import android.os.Bundle
                    import androidx.activity.ComponentActivity
                    import androidx.activity.compose.setContent
                    import androidx.compose.foundation.layout.Box
                    import androidx.compose.foundation.layout.fillMaxSize
                    import androidx.compose.material3.MaterialTheme
                    import androidx.compose.material3.Surface
                    import androidx.compose.material3.Text
                    import androidx.compose.ui.Alignment
                    import androidx.compose.ui.Modifier

                    class MainActivity : ComponentActivity() {
                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            setContent {
                                MaterialTheme {
                                    Surface(Modifier.fillMaxSize()) {
                                        Box(contentAlignment = Alignment.Center) { Text("Hello from $name") }
                                    }
                                }
                            }
                        }
                    }
                """)
                put("app/src/test/java/${pkg.replace('.', '/')}/ExampleTest.kt", """
                    package $pkg

                    import org.junit.Assert.assertEquals
                    import org.junit.Test

                    class ExampleTest {
                        @Test fun addition() = assertEquals(4, 2 + 2)
                    }
                """)
                put(".gitignore", "build/\n.gradle/\nlocal.properties")
            }
            WEB_STATIC -> {
                put("index.html", """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="utf-8" />
                      <meta name="viewport" content="width=device-width, initial-scale=1" />
                      <title>$name</title>
                      <link rel="stylesheet" href="style.css" />
                    </head>
                    <body>
                      <main>
                        <h1>$name</h1>
                        <p id="status">Loading…</p>
                        <button id="btn">Click me</button>
                      </main>
                      <script src="app.js"></script>
                    </body>
                    </html>
                """)
                put("style.css", """
                    body { font-family: system-ui, sans-serif; margin: 0; background: #0b0f17; color: #e5e7eb; }
                    main { max-width: 640px; margin: 48px auto; padding: 0 16px; }
                    button { background: #22d3ee; border: 0; padding: 10px 16px; border-radius: 8px; font-weight: 600; }
                """)
                put("app.js", """
                    const status = document.getElementById('status');
                    let clicks = 0;
                    status.textContent = 'Ready';
                    document.getElementById('btn').addEventListener('click', () => {
                      clicks++;
                      status.textContent = 'Clicked ' + clicks + ' time' + (clicks === 1 ? '' : 's');
                      console.log('click', clicks);
                    });
                """)
            }
            WEB_GAME -> {
                put("index.html", """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="utf-8" />
                      <meta name="viewport" content="width=device-width, initial-scale=1" />
                      <title>$name</title>
                      <style>html,body{margin:0;height:100%;background:#000;touch-action:none}canvas{display:block;width:100%;height:100%}</style>
                    </head>
                    <body>
                      <canvas id="game"></canvas>
                      <script src="game.js"></script>
                    </body>
                    </html>
                """)
                put("game.js", """
                    const canvas = document.getElementById('game');
                    const ctx = canvas.getContext('2d');
                    function resize() { canvas.width = innerWidth; canvas.height = innerHeight; }
                    addEventListener('resize', resize); resize();
                    const ball = { x: 100, y: 100, vx: 3, vy: 2.5, r: 14 };
                    let score = 0;
                    canvas.addEventListener('pointerdown', (e) => {
                      const dx = e.clientX - ball.x, dy = e.clientY - ball.y;
                      if (dx * dx + dy * dy < (ball.r * 3) ** 2) { score++; ball.vx *= 1.1; ball.vy *= 1.1; }
                    });
                    function frame() {
                      ball.x += ball.vx; ball.y += ball.vy;
                      if (ball.x < ball.r || ball.x > canvas.width - ball.r) ball.vx *= -1;
                      if (ball.y < ball.r || ball.y > canvas.height - ball.r) ball.vy *= -1;
                      ctx.fillStyle = '#0b0f17'; ctx.fillRect(0, 0, canvas.width, canvas.height);
                      ctx.fillStyle = '#22d3ee'; ctx.beginPath(); ctx.arc(ball.x, ball.y, ball.r, 0, Math.PI * 2); ctx.fill();
                      ctx.fillStyle = '#e5e7eb'; ctx.font = '20px sans-serif'; ctx.fillText('Score: ' + score, 16, 32);
                      requestAnimationFrame(frame);
                    }
                    frame();
                """)
            }
            KOTLIN_CONSOLE -> put("src/Main.kt", """
                fun main(args: Array<String>) {
                    println("Hello from $name")
                }
            """)
            PYTHON -> {
                put("main.py", """
                    def greet(name: str) -> str:
                        return f"Hello, {name}!"


                    if __name__ == "__main__":
                        print(greet("$name"))
                """)
                put("test_main.py", """
                    import unittest
                    from main import greet


                    class GreetTest(unittest.TestCase):
                        def test_greet(self):
                            self.assertEqual(greet("A"), "Hello, A!")


                    if __name__ == "__main__":
                        unittest.main()
                """)
            }
            WEB_APP -> {
                put("index.html", """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="utf-8" />
                      <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
                      <title>$name</title>
                      <link rel="stylesheet" href="style.css" />
                    </head>
                    <body>
                      <main class="center">
                        <h1>$name</h1>
                        <p>Gravity is building this app…</p>
                      </main>
                      <script src="app.js"></script>
                    </body>
                    </html>
                """)
                put("style.css", """
                    :root { color-scheme: light dark; font-family: system-ui, sans-serif; }
                    body { margin: 0; }
                    .center { min-height: 100vh; display: grid; place-content: center; text-align: center; padding: 24px; }
                """)
                put("app.js", "// App logic goes here.")
            }
            SHELL -> {
                put("build.sh", """
                    #!/system/bin/sh
                    set -e
                    mkdir -p dist
                    echo "built at ${'$'}(date)" > dist/build.txt
                    echo "Build OK"
                """)
                put("test.sh", """
                    #!/system/bin/sh
                    sh build.sh >/dev/null
                    if [ -f dist/build.txt ]; then echo "PASS"; exit 0; else echo "FAIL"; exit 1; fi
                """)
            }
        }
    }
}
