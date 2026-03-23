package com.sj.obd2app.ui

import android.content.Context
import android.widget.Toast

/**
 * Utility functions for UI operations.
 */

/**
 * Show a Toast message.
 */
fun showToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
