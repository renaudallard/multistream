package it.allard.multistream.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Build a ViewModel from the hand-written graph without a DI framework. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(noinline create: () -> VM): VM =
    viewModel(factory = viewModelFactory { initializer { create() } })
