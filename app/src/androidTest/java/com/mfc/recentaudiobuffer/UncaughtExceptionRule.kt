/*
 * Auto Record Moments
 * Copyright (C) 2025 Morten Fjord Christensen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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