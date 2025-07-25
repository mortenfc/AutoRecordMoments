package com.mfc.recentaudiobuffer

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicReference

/**
 * A JUnit TestRule that captures uncaught exceptions from any thread
 * and fails the test if one occurs.
 */
class UncaughtExceptionRule : TestRule {
    private val uncaughtException = AtomicReference<Throwable?>(null)

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
                try {
                    // Set a custom handler to capture exceptions
                    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
                        uncaughtException.set(throwable)
                    }

                    // Run the actual test
                    base.evaluate()

                    // After the test, check if our handler caught anything
                    val exception = uncaughtException.get()
                    if (exception != null) {
                        throw AssertionError(
                            "An uncaught exception was thrown in a background thread.",
                            exception
                        )
                    }
                } finally {
                    // Restore the original handler
                    Thread.setDefaultUncaughtExceptionHandler(originalHandler)
                    uncaughtException.set(null)
                }
            }
        }
    }
}