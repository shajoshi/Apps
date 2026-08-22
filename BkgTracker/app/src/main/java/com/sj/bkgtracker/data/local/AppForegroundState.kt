package com.sj.bkgtracker.data.local

object AppForegroundState {
    @Volatile
    var isInForeground: Boolean = false
}
