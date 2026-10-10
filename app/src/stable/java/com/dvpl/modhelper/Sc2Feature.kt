package com.dvpl.modhelper

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable


object Sc2Feature {

    @Composable
    fun RegisterLauncher(context: Context, queryFileName: (Uri) -> String?) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { }
    }
    @Composable
    fun MaybeScreen(outputDirUri: Uri?): Boolean {
        return false
    }
    @Composable
    fun Button(isProcessing: Boolean) { }
}
