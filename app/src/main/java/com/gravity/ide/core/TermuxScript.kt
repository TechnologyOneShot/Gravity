package com.gravity.ide.core

/**
 * Bash script run inside Termux for a companion build. Pure so it can be executed in JVM
 * unit tests against a fake project (see TermuxScriptTest).
 *
 * Steps: sync the shared copy into $HOME/gravity-builds/<dir>, use ./gradlew or Termux's
 * gradle, point AGP at Termux's aapt2 and $ANDROID_HOME, run the build, copy artifacts that
 * match [artifactGlob] to <share>/<dir>/.gravity-out and exit with the build's exit code.
 */
object TermuxScript {
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun build(sharePath: String, dirName: String, command: String, artifactGlob: String, clean: Boolean): String {
        val buildCmd = command.replace("./gradlew", "\"\$GRAVITY_GRADLE\"")
        return buildString {
            appendLine("set -o pipefail")
            appendLine("shopt -s globstar nullglob")
            appendLine("[ -f \"\$HOME/.gravity-env\" ] && . \"\$HOME/.gravity-env\"")
            appendLine("SRC=${quote("$sharePath/$dirName")}")
            appendLine("WORK=\"\$HOME/gravity-builds/\"${quote(dirName)}")
            if (clean) appendLine("rm -rf \"\$WORK\"")
            appendLine("mkdir -p \"\$WORK\" || exit 90")
            appendLine("echo \"[gravity] sync \$SRC -> \$WORK\"")
            appendLine("cp -r \"\$SRC/.\" \"\$WORK/\" || exit 91")
            appendLine("cd \"\$WORK\" || exit 92")
            appendLine("if [ -f gradlew ]; then chmod +x gradlew; GRAVITY_GRADLE=./gradlew; elif command -v gradle >/dev/null; then GRAVITY_GRADLE=gradle; else GRAVITY_GRADLE=./gradlew; fi")
            appendLine("if command -v aapt2 >/dev/null && ! grep -q aapt2FromMavenOverride gradle.properties 2>/dev/null; then echo \"android.aapt2FromMavenOverride=\$(command -v aapt2)\" >> gradle.properties; echo '[gravity] using Termux aapt2'; fi")
            appendLine("if [ -n \"\$ANDROID_HOME\" ] && [ ! -f local.properties ]; then echo \"sdk.dir=\$ANDROID_HOME\" > local.properties; fi")
            appendLine("echo '[gravity] run:' ${quote(command)}")
            appendLine(buildCmd)
            appendLine("code=\$?")
            appendLine("echo \"[gravity] build exit code \$code\"")
            appendLine("rm -rf \"\$SRC/.gravity-out\"; mkdir -p \"\$SRC/.gravity-out\"")
            appendLine("n=0; for f in $artifactGlob; do cp \"\$f\" \"\$SRC/.gravity-out/\" && n=\$((n+1)); done")
            appendLine("echo \"[gravity] exported \$n artifact(s)\"")
            appendLine("exit \$code")
        }
    }
}
