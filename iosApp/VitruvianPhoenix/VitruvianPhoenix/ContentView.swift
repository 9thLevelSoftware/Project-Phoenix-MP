import SwiftUI
import shared

/// Main ContentView that hosts the Compose Multiplatform UI
struct ContentView: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ComposeView()
            .id(colorScheme)
            .ignoresSafeArea(.all)
    }
}

/// UIViewControllerRepresentable wrapper for Compose Multiplatform
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

#Preview {
    ContentView()
}
