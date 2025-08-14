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

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * A Timber tree for release builds that reports warnings and errors to Crashlytics.
 */
class CrashReportingTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.WARN) {
            return // Don't log INFO, DEBUG, or VERBOSE
        }

        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.log(message) // Log the message as a breadcrumb

        if (t != null) {
            crashlytics.recordException(t) // Log the full exception
        }
    }
}

/**
 * A Timber tree for staging builds that logs INFO and higher to Logcat.
 */
class InfoLogTree : Timber.DebugTree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean {
        // Only log INFO, WARN, ERROR, and ASSERT.
        return priority >= Log.INFO
    }
}