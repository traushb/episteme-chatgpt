//
//  FirebaseAvailability.swift
//  Reader
//
//  Firebase is configured only when GoogleService-Info.plist ships in the
//  app (see ReaderApp). Without it `Auth.auth()` stops the app with
//  `fatalError` instead of returning a signed-out instance, so code that runs
//  at launch or on foreground before any sign-in reads the user from here.
//

#if canImport(FirebaseCore)
import FirebaseCore
#endif
#if canImport(FirebaseAuth)
import FirebaseAuth
#endif

enum FirebaseAvailability {
    nonisolated static var isConfigured: Bool {
#if canImport(FirebaseCore)
        FirebaseApp.app() != nil
#else
        false
#endif
    }

#if canImport(FirebaseAuth)
    /// The signed-in Firebase user; nil when signed out or when Firebase is
    /// not configured in this build.
    nonisolated static var currentUser: User? {
        isConfigured ? Auth.auth().currentUser : nil
    }
#endif
}
