package com.patjackson.latertext

import androidx.compose.runtime.Composable
import com.patjackson.latertext.core.model.RecurrenceFrequency
import javax.inject.Inject
import javax.inject.Singleton

/** Release builds contain neither the drawer nor the analytics SDK. */
@Singleton
class LaterTextDiagnostics @Inject constructor() {
    fun install() = Unit
    fun screenViewed(screen: AppScreen) = Unit
    fun scheduleSaved(success: Boolean, frequency: RecurrenceFrequency, hasMedia: Boolean) = Unit
    fun scheduleChanged(operation: DiagnosticOperation) = Unit
    @Composable fun Host(content: @Composable () -> Unit) = content()
}
