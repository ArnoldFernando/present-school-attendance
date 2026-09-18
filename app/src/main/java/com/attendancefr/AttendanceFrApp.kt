package com.attendancefr

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry. Hilt is initialized here so every @AndroidEntryPoint
 * Activity / ViewModel can be constructed without a manual component graph.
 *
 * Choice of DI: Hilt (documented in ARCHITECTURE.md). Manual DI was
 * considered but Hilt keeps ViewModel factories and Room singletons honest
 * with almost no extra code.
 */
@HiltAndroidApp
class AttendanceFrApp : Application()
