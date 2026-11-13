package com.nettwork.policethief

import androidx.compose.runtime.Composable
import com.nettwork.policethief.presentation.navigation.AppNavHost
import com.nettwork.policethief.presentation.theme.PoliceThiefTheme

/** Android(MainActivity)와 iOS(MainViewController)가 공통으로 띄우는 최상위 Composable. Koin 은 진입점에서 먼저 시작해야 한다. */
@Composable
fun App() {
    PoliceThiefTheme {
        AppNavHost()
    }
}
