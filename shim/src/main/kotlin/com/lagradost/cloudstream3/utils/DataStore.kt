package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences

object DataStore {
    fun Context.getSharedPrefs(): SharedPreferences = getSharedPreferences("rebuild_preference", 0)
}
