package com.sj.obd2app.obd

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Abstraction for surfacing short status messages to the user.
 *
 * Extracted so the connection flow can be unit-tested without pulling in
 * the Android Toast API (which requires a Looper).
 */
interface UserNotifier {
    fun toast(message: String, long: Boolean = false)
}

/** Production default: shows an Android [Toast] on the main thread. */
class ToastUserNotifier(private val context: Context?) : UserNotifier {
    override fun toast(message: String, long: Boolean) {
        val ctx = context ?: return
        val duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(ctx, message, duration).show()
        }
    }
}

/** Test / headless default: silently drops messages. */
object NoOpUserNotifier : UserNotifier {
    override fun toast(message: String, long: Boolean) = Unit
}
