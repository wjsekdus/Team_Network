import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        // Kotlin 쪽 의존성(Koin)과 서버 주소 설정을 먼저 준비한다.
        MainViewControllerKt.startKoinForIos()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
