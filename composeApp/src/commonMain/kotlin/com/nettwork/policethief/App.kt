package com.nettwork.policethief

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nettwork.policethief.presentation.components.NoticeHost
import com.nettwork.policethief.presentation.navigation.AppNavHost
import com.nettwork.policethief.presentation.theme.PoliceThiefTheme

/** Android(MainActivity)와 iOS(MainViewController)가 공통으로 띄우는 최상위 Composable. Koin 은 진입점에서 먼저 시작해야 한다. */
@Composable
fun App() {
    PoliceThiefTheme {
        Box(Modifier.fillMaxSize()) {
            AppNavHost()
            // 경기 이벤트 배너: 어느 화면에 있든 위에 겹쳐 보인다(알림 권한이 없어도 동작).
            NoticeHost()
        }
    }
}
