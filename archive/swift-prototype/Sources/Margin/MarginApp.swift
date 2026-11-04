import SwiftUI

@main
struct MarginApp: App {
    @State private var store = RepoStore()

    var body: some Scene {
        WindowGroup {
            Text("Margin")
                .environment(store)
        }
    }
}
