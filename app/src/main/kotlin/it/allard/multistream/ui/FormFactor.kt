package it.allard.multistream.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.staticCompositionLocalOf

enum class FormFactor { PHONE, TV }

val LocalFormFactor = staticCompositionLocalOf { FormFactor.PHONE }

fun detectFormFactor(context: Context): FormFactor =
    if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) FormFactor.TV else FormFactor.PHONE
