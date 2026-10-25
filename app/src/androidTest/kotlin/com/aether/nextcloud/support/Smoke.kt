package com.aether.nextcloud.support

/**
 * Marks an instrumented test as part of the pull request smoke set: a handful of fast tests that cover the
 * parts JVM tests cannot (Room migrations, the SAF provider, the upload worker). Everything else runs on
 * release branches. CI selects these with `-Pandroid.testInstrumentationRunnerArguments.annotation=`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Smoke
