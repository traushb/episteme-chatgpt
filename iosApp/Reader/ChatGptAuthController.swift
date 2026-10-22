//
//  ChatGptAuthController.swift
//  Reader
//
//  "Sign in with ChatGPT" for the PDF answer check. The user signs in with
//  their ChatGPT account and allows this app to use their ChatGPT plan; the
//  resulting OAuth access token is a bearer credential for the Responses API
//  whose usage counts against that plan instead of an API key.
//
//  OpenAI's flow for open-source apps that run on the user's own device:
//  OpenID Connect authorization code + PKCE against auth.openai.com, with
//  dynamic registration (the first authorization uses a placeholder client ID
//  and the callback returns the client ID issued to this install) and an
//  IPv4 loopback redirect. ASWebAuthenticationSession cannot observe an
//  http://127.0.0.1 redirect, so a tiny listener bound to 127.0.0.1 receives
//  the callback and answers with a redirect to a private scheme, which is
//  what closes the browser sheet.
//
//  Swift owns the browser, the Keychain and token refresh. Kotlin only asks
//  for a fresh access token right before each request (IosChatGptBridge) and
//  renders the published account state; credentials never cross into
//  Compose state.
//

import AuthenticationServices
import CryptoKit
import Darwin
import Foundation
import OSLog
import ReaderShared
import Security
import UIKit

private let chatGptLogger = Logger(subsystem: "com.aryan.reader", category: "ChatGPT")

@MainActor
final class ChatGptAuthController: NSObject {
    private enum Config {
        /// Shown on OpenAI's consent screen when this install registers itself.
        static let appName = "Episteme Reader"
        static let issuer = "https://auth.openai.com"
        static let issuerHost = "auth.openai.com"
        static let resource = "https://api.openai.com/v1"
        static let scopes = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        /// The scope that lets requests draw on the user's ChatGPT plan.
        static let planUsageScope = "chatgpt.tokens.use.direct"
        /// Placeholder for the first authorization; it is never stored or exchanged.
        static let registrationClientId = "dynamic_agent_client"
        static let callbackPath = "/auth/callback"
        static let completionScheme = "episteme-chatgpt"
        /// Renew this long before expiry so a request never leaves with a token that dies in flight.
        static let refreshMargin: TimeInterval = 60
        static let keychainService = "com.aryan.reader.chatgpt.v1"
        static let connectionAccount = "connection"
        static let registrationAccount = "registration"
        /// Codes after which the saved session cannot be renewed.
        static let reauthenticationCodes: Set<String> = [
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused", "invalid_client",
            "invalid_id_token", "invalid_token_response", "account_mismatch",
        ]
    }

    private struct Discovery {
        let authorizationEndpoint: URL
        let tokenEndpoint: URL
        let revocationEndpoint: URL?
    }

    /// The client ID issued to this install. It outlives sign-out so signing in
    /// again reuses the registration instead of registering the app twice.
    private struct Registration: Codable {
        var clientId: String
        var email: String?
    }

    private struct Connection: Codable {
        var clientId: String
        var subject: String
        var email: String?
        var name: String?
        var scopes: [String]
        var accessToken: String
        var refreshToken: String?
        var expiresAt: Date
        var earliestRefreshAt: Date?
        var needsReauth: Bool

        var planUsageAllowed: Bool { scopes.contains(Config.planUsageScope) }
    }

    private struct TokenGrant {
        let accessToken: String
        let refreshToken: String?
        let expiresAt: Date
        let earliestRefreshAt: Date?
        let scopes: [String]
        let idToken: String?
    }

    private struct Identity {
        let subject: String
        let email: String?
        let name: String?
    }

    private enum TokenOutcome: Sendable {
        case token(String)
        case failure(String)
    }

    private weak var bridge: ReaderIosBridge?
    private var connection: Connection?
    private var registration: Registration?
    private var discovery: Discovery?
    private var signInTask: Task<Void, Never>?
    private var activeAttempt: ChatGptBrowserAttempt?
    private var refreshTask: Task<TokenOutcome, Never>?
    private var signInBackgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var lastErrorCode: String?

    func attach(to bridge: ReaderIosBridge) {
        guard self.bridge !== bridge else { return }
        self.bridge = bridge
        connection = Self.loadKeychainValue(Connection.self, account: Config.connectionAccount)
        registration = Self.loadKeychainValue(Registration.self, account: Config.registrationAccount)
        // The bridge keeps these handlers for the app's lifetime and they keep
        // this controller alive with them, like the other native controllers.
        bridge.chatGpt.setHandlers(
            signIn: {
                Task { @MainActor in self.signIn() }
            },
            signOut: {
                Task { @MainActor in await self.signOut() }
            },
            accessToken: { completion in
                Task { @MainActor in
                    let outcome = await self.freshAccessToken()
                    switch outcome {
                    case .token(let token): completion(token, nil)
                    case .failure(let code): completion(nil, code)
                    }
                }
            }
        )
        publish()
    }

    // MARK: - Sign in

    func signIn() {
        guard signInTask == nil else { return }
        lastErrorCode = nil
        signInTask = Task { @MainActor in
            do {
                try await self.performSignIn()
                self.lastErrorCode = nil
                let planUsageAllowed = self.connection?.planUsageAllowed ?? false
                chatGptLogger.info("signin.ok planUsage=\(planUsageAllowed, privacy: .public)")
            } catch let error as ChatGptAuthError {
                let code = error.code
                self.lastErrorCode = code == ChatGptAuthError.cancelled.code ? nil : code
                chatGptLogger.error("signin.failed code=\(code, privacy: .public)")
            } catch {
                self.lastErrorCode = "sign_in_failed"
                chatGptLogger.error("signin.failed unexpected")
            }
            self.signInTask = nil
            self.publish()
        }
        publish()
    }

    private func performSignIn() async throws {
        let provider = try await loadDiscovery()
        // Signed in without allowing plan usage: ask for consent again so the
        // user gets the choice back instead of the same silent grant.
        let reconsent = connection.map { !$0.needsReauth && !$0.planUsageAllowed } ?? false
        let state = try Self.randomToken()
        let nonce = try Self.randomToken()
        let verifier = try Self.randomToken()
        let challenge = Self.base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))

        let attempt = ChatGptBrowserAttempt()
        guard let loopback = startChatGptLoopbackServer(
            expectedState: state,
            callbackPath: Config.callbackPath,
            completionURL: "\(Config.completionScheme)://auth/complete",
            onCallback: { callback in
                Task { @MainActor in attempt.deliver(callback) }
            }
        ) else {
            throw ChatGptAuthError("callback_port_unavailable")
        }
        defer { stopChatGptLoopbackServer(loopback) }
        let redirectURI = "http://127.0.0.1:\(loopback.port)\(Config.callbackPath)"

        let savedClientId = registration?.clientId
        var query: [(String, String)] = [
            ("client_id", savedClientId ?? Config.registrationClientId),
            ("response_type", "code"),
            ("redirect_uri", redirectURI),
            ("scope", Config.scopes),
            ("resource", Config.resource),
            ("state", state),
            ("nonce", nonce),
            ("code_challenge_method", "S256"),
            ("code_challenge", challenge),
        ]
        if savedClientId == nil { query.append(("agent_name_hint", Config.appName)) }
        if let email = registration?.email ?? connection?.email, !email.isEmpty {
            query.append(("login_hint", email))
        }
        if reconsent { query.append(("prompt", "consent")) }
        guard var components = URLComponents(url: provider.authorizationEndpoint, resolvingAgainstBaseURL: false) else {
            throw ChatGptAuthError("discovery_failed")
        }
        let existingQuery = components.percentEncodedQuery.map { $0.isEmpty ? "" : "\($0)&" } ?? ""
        components.percentEncodedQuery = existingQuery + Self.formEncode(query)
        guard let authorizationURL = components.url else { throw ChatGptAuthError("discovery_failed") }

        // The user may leave for a password manager or a mail code; keep the
        // listener alive for the usual background grace period meanwhile.
        signInBackgroundTask = UIApplication.shared.beginBackgroundTask(withName: "ChatGPT sign-in") { [weak self] in
            // UIKit calls the expiration handler on the main thread.
            MainActor.assumeIsolated { self?.endSignInBackgroundTask() }
        }
        defer { endSignInBackgroundTask() }
        activeAttempt = attempt
        defer { activeAttempt = nil }
        let callback = try await attempt.run(url: authorizationURL, completionScheme: Config.completionScheme)

        let code: String
        let returnedClientId: String?
        switch callback {
        case .authorized(let authorizedCode, let clientId):
            code = authorizedCode
            returnedClientId = clientId
        case .denied(let error):
            throw error == "access_denied" ? ChatGptAuthError.cancelled : ChatGptAuthError(error)
        case .failed(let reason):
            throw ChatGptAuthError(reason)
        }
        guard let clientId = returnedClientId ?? savedClientId,
              Self.isValidClientId(clientId),
              savedClientId == nil || returnedClientId == nil || savedClientId == returnedClientId else {
            throw ChatGptAuthError("registration_incomplete")
        }
        // Keep the issued registration before the one-time code exchange: if the
        // exchange fails, the next attempt reuses it instead of registering again.
        if savedClientId != clientId {
            saveRegistration(Registration(clientId: clientId, email: registration?.email))
        }

        let grant: TokenGrant
        do {
            let response = try await postForm(provider.tokenEndpoint, fields: [
                ("grant_type", "authorization_code"),
                ("client_id", clientId),
                ("code", code),
                ("code_verifier", verifier),
                ("redirect_uri", redirectURI),
                ("resource", Config.resource),
            ])
            grant = try Self.parseTokenGrant(response, previousScopes: nil, previousRefreshToken: nil)
        } catch let error as ChatGptAuthError where error.code == "invalid_client" {
            // The saved registration is no longer accepted; start clean next time.
            clearRegistration()
            throw error
        }
        guard let idToken = grant.idToken else { throw ChatGptAuthError("invalid_id_token") }
        let identity = try Self.verifyIdToken(idToken, clientId: clientId, nonce: nonce)
        let signedIn = Connection(
            clientId: clientId,
            subject: identity.subject,
            email: identity.email,
            name: identity.name,
            scopes: grant.scopes,
            accessToken: grant.accessToken,
            refreshToken: grant.refreshToken,
            expiresAt: grant.expiresAt,
            earliestRefreshAt: grant.earliestRefreshAt,
            needsReauth: false
        )
        saveConnection(signedIn)
        saveRegistration(Registration(clientId: clientId, email: identity.email ?? registration?.email))
    }

    private func endSignInBackgroundTask() {
        guard signInBackgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(signInBackgroundTask)
        signInBackgroundTask = .invalid
    }

    // MARK: - Sign out

    func signOut() async {
        activeAttempt?.cancel()
        refreshTask?.cancel()
        guard let current = connection else {
            publish()
            return
        }
        connection = nil
        Self.deleteKeychainValue(account: Config.connectionAccount)
        lastErrorCode = nil
        publish()
        // Revoking the refresh token ends the renewable session on OpenAI's
        // side too. Best effort: the local credentials are already gone, and
        // the app can always be disconnected in ChatGPT's settings.
        guard let refreshToken = current.refreshToken,
              let endpoint = try? await loadDiscovery().revocationEndpoint else { return }
        let revoked = try? await postForm(endpoint, fields: [
            ("token", refreshToken),
            ("token_type_hint", "refresh_token"),
            ("client_id", current.clientId),
        ])
        let revokeStatus = revoked?.1 ?? 0
        chatGptLogger.info("signout.revoke status=\(revokeStatus, privacy: .public)")
    }

    // MARK: - Access tokens

    private func freshAccessToken() async -> TokenOutcome {
        guard let current = connection else { return .failure("sign_in_required") }
        if current.needsReauth { return .failure("reauth_required") }
        if !current.planUsageAllowed { return .failure("plan_usage_not_allowed") }
        let now = Date()
        if current.expiresAt.timeIntervalSince(now) > Config.refreshMargin {
            return .token(current.accessToken)
        }
        // The server may ask clients not to renew before a given time; the
        // current token stays good until it actually expires.
        if current.expiresAt > now, let earliest = current.earliestRefreshAt, earliest > now {
            return .token(current.accessToken)
        }
        // One refresh at a time: refresh tokens rotate, so two concurrent
        // refreshes would burn the token the second one needs.
        let task = refreshTask ?? Task { @MainActor in await self.refreshAccessToken() }
        refreshTask = task
        let outcome = await task.value
        if refreshTask == task { refreshTask = nil }
        return outcome
    }

    private func refreshAccessToken() async -> TokenOutcome {
        guard let current = connection else { return .failure("sign_in_required") }
        guard let refreshToken = current.refreshToken else {
            markNeedsReauth()
            return .failure("reauth_required")
        }
        do {
            let provider = try await loadDiscovery()
            let response = try await postForm(provider.tokenEndpoint, fields: [
                ("grant_type", "refresh_token"),
                ("client_id", current.clientId),
                ("refresh_token", refreshToken),
                ("resource", Config.resource),
            ])
            let grant = try Self.parseTokenGrant(response, previousScopes: current.scopes, previousRefreshToken: refreshToken)
            var identity: Identity?
            if let idToken = grant.idToken {
                let refreshedIdentity = try Self.verifyIdToken(idToken, clientId: current.clientId, nonce: nil)
                guard refreshedIdentity.subject == current.subject else { throw ChatGptAuthError("account_mismatch") }
                identity = refreshedIdentity
            }
            // Signed out (or switched accounts) while the refresh was in flight.
            guard connection?.subject == current.subject, connection?.clientId == current.clientId else {
                return .failure("sign_in_required")
            }
            var renewed = current
            renewed.accessToken = grant.accessToken
            renewed.refreshToken = grant.refreshToken
            renewed.expiresAt = grant.expiresAt
            renewed.earliestRefreshAt = grant.earliestRefreshAt
            renewed.scopes = grant.scopes
            if let identity {
                renewed.email = identity.email ?? renewed.email
                renewed.name = identity.name ?? renewed.name
            }
            saveConnection(renewed)
            return renewed.planUsageAllowed ? .token(renewed.accessToken) : .failure("plan_usage_not_allowed")
        } catch let error as ChatGptAuthError {
            let code = error.code
            chatGptLogger.error("refresh.failed code=\(code, privacy: .public)")
            if Config.reauthenticationCodes.contains(code) { markNeedsReauth() }
            return .failure(code)
        } catch {
            return .failure("network_error")
        }
    }

    private func markNeedsReauth() {
        guard var current = connection else { return }
        current.needsReauth = true
        current.refreshToken = nil
        saveConnection(current)
    }

    // MARK: - Persistence and publishing

    private func saveConnection(_ value: Connection) {
        connection = value
        if !Self.storeKeychainValue(value, account: Config.connectionAccount) {
            chatGptLogger.error("keychain.write_failed account=connection")
        }
        publish()
    }

    private func saveRegistration(_ value: Registration) {
        registration = value
        _ = Self.storeKeychainValue(value, account: Config.registrationAccount)
    }

    private func clearRegistration() {
        registration = nil
        Self.deleteKeychainValue(account: Config.registrationAccount)
    }

    private func publish() {
        guard let bridge else { return }
        let status: String
        if signInTask != nil {
            status = "CONNECTING"
        } else if let connection {
            status = connection.needsReauth ? "REAUTH_REQUIRED" : "CONNECTED"
        } else {
            status = "DISCONNECTED"
        }
        bridge.chatGpt.updateConnection(
            status: status,
            email: connection?.email,
            name: connection?.name,
            planUsageAllowed: connection?.planUsageAllowed ?? false,
            errorCode: lastErrorCode
        )
    }

    // MARK: - OAuth plumbing

    private func loadDiscovery() async throws -> Discovery {
        if let discovery { return discovery }
        guard let url = URL(string: "\(Config.issuer)/.well-known/openid-configuration") else {
            throw ChatGptAuthError("discovery_failed")
        }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(from: url)
        } catch {
            throw ChatGptAuthError.network
        }
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              json["issuer"] as? String == Config.issuer,
              let authorization = Self.issuerURL(json["authorization_endpoint"]),
              let token = Self.issuerURL(json["token_endpoint"]) else {
            throw ChatGptAuthError("discovery_failed")
        }
        let resolved = Discovery(
            authorizationEndpoint: authorization,
            tokenEndpoint: token,
            revocationEndpoint: Self.issuerURL(json["revocation_endpoint"])
        )
        discovery = resolved
        return resolved
    }

    /// Endpoints must stay on the issuer: a discovery document pointing
    /// elsewhere would send codes and refresh tokens to another origin.
    private static func issuerURL(_ value: Any?) -> URL? {
        guard let string = value as? String,
              let url = URL(string: string),
              url.scheme == "https",
              url.host() == Config.issuerHost else { return nil }
        return url
    }

    private func postForm(_ endpoint: URL, fields: [(String, String)]) async throws -> (Data, Int) {
        var request = URLRequest(url: endpoint, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = Data(Self.formEncode(fields).utf8)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw ChatGptAuthError.network
        }
        guard let http = response as? HTTPURLResponse else { throw ChatGptAuthError.network }
        // Answers must come from the endpoint discovery vouched for.
        guard http.url?.host() == endpoint.host() else { throw ChatGptAuthError("unexpected_redirect") }
        return (data, http.statusCode)
    }

    private static func parseTokenGrant(
        _ result: (Data, Int),
        previousScopes: [String]?,
        previousRefreshToken: String?
    ) throws -> TokenGrant {
        let (data, status) = result
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard status == 200, let json else {
            throw ChatGptAuthError(oauthErrorCode(json) ?? (status >= 500 ? "server_error" : "token_request_failed"))
        }
        // A refresh may omit the scope when the grant did not change.
        guard let scope = (json["scope"] as? String) ?? previousScopes?.joined(separator: " ") else {
            throw ChatGptAuthError("invalid_token_response")
        }
        let scopes = scope.split(separator: " ").map(String.init)
        guard let accessToken = json["access_token"] as? String, !accessToken.isEmpty,
              (json["token_type"] as? String)?.lowercased() == "bearer",
              let expiresIn = (json["expires_in"] as? NSNumber)?.doubleValue,
              expiresIn.isFinite, expiresIn > 0 else {
            throw ChatGptAuthError("invalid_token_response")
        }
        let refreshToken = (json["refresh_token"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? previousRefreshToken
        if scopes.contains("offline_access") && refreshToken == nil {
            throw ChatGptAuthError("invalid_token_response")
        }
        return TokenGrant(
            accessToken: accessToken,
            refreshToken: refreshToken,
            expiresAt: Date().addingTimeInterval(expiresIn),
            earliestRefreshAt: parseInstant(json["earliest_refresh_at"]),
            scopes: scopes,
            idToken: (json["id_token"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        )
    }

    /// Reads the claims of an ID token received directly from the token
    /// endpoint over TLS. OpenID Connect lets such a token be validated by
    /// the TLS connection instead of its signature (Core 1.0, 3.1.3.7), so
    /// the checks here are the claims: issuer, audience, nonce and expiry.
    private static func verifyIdToken(_ idToken: String, clientId: String, nonce: String?) throws -> Identity {
        let segments = idToken.split(separator: ".", omittingEmptySubsequences: false)
        guard segments.count == 3,
              let payload = base64URLDecode(String(segments[1])),
              let claims = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any] else {
            throw ChatGptAuthError("invalid_id_token")
        }
        let audiences = (claims["aud"] as? String).map { [$0] } ?? (claims["aud"] as? [String]) ?? []
        let authorizedParty = claims["azp"] as? String
        guard claims["iss"] as? String == Config.issuer,
              audiences.contains(clientId),
              authorizedParty == nil || authorizedParty == clientId,
              audiences.count <= 1 || authorizedParty == clientId,
              nonce == nil || claims["nonce"] as? String == nonce,
              let expiry = (claims["exp"] as? NSNumber)?.doubleValue,
              Date(timeIntervalSince1970: expiry).addingTimeInterval(300) > Date(),
              let subject = claims["sub"] as? String, !subject.isEmpty else {
            throw ChatGptAuthError("invalid_id_token")
        }
        return Identity(
            subject: subject,
            email: (claims["email"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            name: (claims["name"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        )
    }

    private static func oauthErrorCode(_ json: [String: Any]?) -> String? {
        let raw = (json?["error"] as? String)
            ?? ((json?["error"] as? [String: Any])?["code"] as? String)
        guard let raw, (1...100).contains(raw.count), raw.unicodeScalars.allSatisfy(isCodeCharacter) else {
            return nil
        }
        return raw
    }

    private static func isCodeCharacter(_ scalar: Unicode.Scalar) -> Bool {
        scalar.isASCII && (CharacterSet.alphanumerics.contains(scalar) || "_.:-".unicodeScalars.contains(scalar))
    }

    private static func parseInstant(_ value: Any?) -> Date? {
        if let seconds = (value as? NSNumber)?.doubleValue, seconds.isFinite, seconds > 0 {
            return Date(timeIntervalSince1970: seconds)
        }
        guard let text = value as? String else { return nil }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: text) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: text)
    }

    private static func isValidClientId(_ clientId: String) -> Bool {
        guard clientId != Config.registrationClientId, (1...200).contains(clientId.count) else { return false }
        return clientId.unicodeScalars.allSatisfy { scalar in
            scalar.isASCII && (CharacterSet.alphanumerics.contains(scalar) || scalar == "_" || scalar == "-")
        }
    }

    private static func randomToken() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw ChatGptAuthError("random_unavailable")
        }
        return base64URL(Data(bytes))
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func base64URLDecode(_ value: String) -> Data? {
        var base64 = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 { base64 += String(repeating: "=", count: 4 - remainder) }
        return Data(base64Encoded: base64)
    }

    /// Percent-encodes everything but RFC 3986 unreserved characters, so a
    /// "+" in an email or token can never be read back as a space.
    private static func formEncode(_ fields: [(String, String)]) -> String {
        let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        func encode(_ text: String) -> String {
            text.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
        }
        return fields.map { "\(encode($0.0))=\(encode($0.1))" }.joined(separator: "&")
    }

    // MARK: - Keychain

    private static func keychainQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Config.keychainService,
            kSecAttrAccount as String: account,
        ]
    }

    private static func loadKeychainValue<Value: Decodable>(_ type: Value.Type, account: String) -> Value? {
        var query = keychainQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private static func storeKeychainValue<Value: Encodable>(_ value: Value, account: String) -> Bool {
        guard let data = try? JSONEncoder().encode(value) else { return false }
        let query = keychainQuery(account: account)
        // Tokens stay on this device: excluded from backups and other devices,
        // and readable after first unlock so a background refresh can run.
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
        return status == errSecSuccess
    }

    private static func deleteKeychainValue(account: String) {
        SecItemDelete(keychainQuery(account: account) as CFDictionary)
    }
}

private struct ChatGptAuthError: Error {
    let code: String

    init(_ code: String) {
        self.code = code
    }

    static let cancelled = ChatGptAuthError("cancelled")
    static let network = ChatGptAuthError("network_error")
}

// MARK: - Browser

/// What the loopback listener received from the browser.
private enum ChatGptLoopbackCallback: Sendable {
    case authorized(code: String, clientId: String?)
    case denied(error: String)
    case failed(reason: String)
}

/// One run of the authorization page. The loopback listener and the browser
/// sheet race to finish it; whichever reports first wins.
@MainActor
private final class ChatGptBrowserAttempt: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var continuation: CheckedContinuation<ChatGptLoopbackCallback, Error>?
    private var session: ASWebAuthenticationSession?

    func run(url: URL, completionScheme: String) async throws -> ChatGptLoopbackCallback {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: completionScheme) { _, error in
                Task { @MainActor in self.browserFinished(error: error) }
            }
            session.presentationContextProvider = self
            // Reuse the ChatGPT login the user already has in Safari.
            session.prefersEphemeralWebBrowserSession = false
            self.session = session
            if !session.start() {
                finish(.failure(ChatGptAuthError("browser_unavailable")))
            }
        }
    }

    func deliver(_ callback: ChatGptLoopbackCallback) {
        finish(.success(callback))
    }

    func cancel() {
        finish(.failure(ChatGptAuthError.cancelled))
    }

    private func browserFinished(error: Error?) {
        if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
            finish(.failure(ChatGptAuthError.cancelled))
            return
        }
        if error != nil {
            finish(.failure(ChatGptAuthError("browser_failed")))
            return
        }
        // The sheet only reaches the completion scheme after the listener
        // received the callback, so its result is already queued ahead of this
        // one. Allow a moment before calling the attempt incomplete.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            self.finish(.failure(ChatGptAuthError("registration_incomplete")))
        }
    }

    private func finish(_ result: Result<ChatGptLoopbackCallback, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        let session = self.session
        self.session = nil
        session?.cancel()
        continuation.resume(with: result)
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            let windows = scenes.flatMap(\.windows)
            if let window = windows.first(where: \.isKeyWindow) ?? windows.first {
                return window
            }
            if let scene = scenes.first {
                return UIWindow(windowScene: scene)
            }
            return ASPresentationAnchor()
        }
    }
}

// MARK: - Loopback listener

private struct ChatGptLoopbackHandle: Sendable {
    let port: UInt16
    /// Write end of the wake pipe; closing it stops the listener thread.
    let wakeFD: Int32
}

/// Binds an ephemeral port on 127.0.0.1 only (never another interface, so no
/// local-network prompt and nothing reachable off the device) and serves the
/// OAuth callback from a dedicated thread. Returns nil if no port is free.
nonisolated private func startChatGptLoopbackServer(
    expectedState: String,
    callbackPath: String,
    completionURL: String,
    onCallback: @escaping @Sendable (ChatGptLoopbackCallback) -> Void
) -> ChatGptLoopbackHandle? {
    let listenFD = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
    guard listenFD >= 0 else { return nil }
    var enabled: Int32 = 1
    setsockopt(listenFD, SOL_SOCKET, SO_REUSEADDR, &enabled, socklen_t(MemoryLayout<Int32>.size))
    var address = sockaddr_in()
    address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    address.sin_family = sa_family_t(AF_INET)
    address.sin_port = in_port_t(0).bigEndian
    guard inet_pton(AF_INET, "127.0.0.1", &address.sin_addr) == 1 else {
        Darwin.close(listenFD)
        return nil
    }
    let bound = withUnsafePointer(to: &address) { pointer in
        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { socketAddress in
            bind(listenFD, socketAddress, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    guard bound == 0, listen(listenFD, 8) == 0 else {
        Darwin.close(listenFD)
        return nil
    }
    var boundAddress = sockaddr_in()
    var boundLength = socklen_t(MemoryLayout<sockaddr_in>.size)
    let named = withUnsafeMutablePointer(to: &boundAddress) { pointer in
        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { socketAddress in
            getsockname(listenFD, socketAddress, &boundLength)
        }
    }
    let port = UInt16(bigEndian: boundAddress.sin_port)
    guard named == 0, port != 0 else {
        Darwin.close(listenFD)
        return nil
    }
    _ = fcntl(listenFD, F_SETFL, fcntl(listenFD, F_GETFL) | O_NONBLOCK)
    var wakePipe: [Int32] = [-1, -1]
    guard pipe(&wakePipe) == 0 else {
        Darwin.close(listenFD)
        return nil
    }
    let wakeReadFD = wakePipe[0]
    let thread = Thread {
        runChatGptLoopbackServer(
            listenFD: listenFD,
            wakeReadFD: wakeReadFD,
            expectedState: expectedState,
            expectedHost: "127.0.0.1:\(port)",
            callbackPath: callbackPath,
            completionURL: completionURL,
            onCallback: onCallback
        )
    }
    thread.name = "ChatGPT sign-in callback"
    thread.start()
    return ChatGptLoopbackHandle(port: port, wakeFD: wakePipe[1])
}

/// Stops the listener. Closing (rather than writing to) the wake pipe cannot
/// raise SIGPIPE even when the listener thread has already exited.
nonisolated private func stopChatGptLoopbackServer(_ handle: ChatGptLoopbackHandle) {
    Darwin.close(handle.wakeFD)
}

nonisolated private func runChatGptLoopbackServer(
    listenFD: Int32,
    wakeReadFD: Int32,
    expectedState: String,
    expectedHost: String,
    callbackPath: String,
    completionURL: String,
    onCallback: @escaping @Sendable (ChatGptLoopbackCallback) -> Void
) {
    defer {
        Darwin.close(listenFD)
        Darwin.close(wakeReadFD)
    }
    // Sign-in pages time out long before this; it only bounds a forgotten listener.
    let deadline = Date().addingTimeInterval(15 * 60)
    while Date() < deadline {
        var descriptors = [
            pollfd(fd: listenFD, events: Int16(POLLIN), revents: 0),
            pollfd(fd: wakeReadFD, events: Int16(POLLIN), revents: 0),
        ]
        let ready = poll(&descriptors, nfds_t(descriptors.count), 1_000)
        if ready < 0 {
            if errno == EINTR { continue }
            onCallback(.failed(reason: "callback_failed"))
            return
        }
        if descriptors[1].revents != 0 { return }
        let listenEvents = descriptors[0].revents
        if listenEvents & Int16(POLLERR | POLLHUP | POLLNVAL) != 0 {
            // iOS can reclaim a listening socket while the app is suspended.
            onCallback(.failed(reason: "callback_failed"))
            return
        }
        guard listenEvents & Int16(POLLIN) != 0 else { continue }
        let clientFD = accept(listenFD, nil, nil)
        guard clientFD >= 0 else { continue }
        let callback = serveChatGptLoopbackRequest(
            clientFD: clientFD,
            expectedState: expectedState,
            expectedHost: expectedHost,
            callbackPath: callbackPath,
            completionURL: completionURL,
            onCallback: onCallback
        )
        Darwin.close(clientFD)
        if callback { return }
    }
    onCallback(.failed(reason: "callback_timeout"))
}

/// Answers one HTTP request. Returns true once the OAuth callback was
/// delivered; unrelated requests (a favicon, a wrong state) are answered
/// and ignored so they cannot end the pending sign-in.
nonisolated private func serveChatGptLoopbackRequest(
    clientFD: Int32,
    expectedState: String,
    expectedHost: String,
    callbackPath: String,
    completionURL: String,
    onCallback: @escaping @Sendable (ChatGptLoopbackCallback) -> Void
) -> Bool {
    // The accepted socket inherits O_NONBLOCK; read it blocking, bounded by a timeout.
    _ = fcntl(clientFD, F_SETFL, fcntl(clientFD, F_GETFL) & ~O_NONBLOCK)
    var enabled: Int32 = 1
    setsockopt(clientFD, SOL_SOCKET, SO_NOSIGPIPE, &enabled, socklen_t(MemoryLayout<Int32>.size))
    var timeout = timeval(tv_sec: 5, tv_usec: 0)
    setsockopt(clientFD, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
    setsockopt(clientFD, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

    var request = [UInt8]()
    var buffer = [UInt8](repeating: 0, count: 4096)
    let headerEnd: [UInt8] = [13, 10, 13, 10]
    while request.count < 16_384 {
        let count = read(clientFD, &buffer, buffer.count)
        if count <= 0 { break }
        request.append(contentsOf: buffer[0..<count])
        if request.containsSubsequence(headerEnd) { break }
    }
    let lines = String(decoding: request, as: UTF8.self).components(separatedBy: "\r\n")
    let requestLine = lines.first?.split(separator: " ") ?? []
    let host = lines.dropFirst()
        .first { $0.lowercased().hasPrefix("host:") }
        .map { $0.dropFirst("host:".count).trimmingCharacters(in: .whitespaces) }
    guard requestLine.count >= 2,
          requestLine[0] == "GET",
          host == expectedHost,
          let components = URLComponents(string: "http://\(expectedHost)\(requestLine[1])"),
          components.path == callbackPath else {
        writeChatGptLoopbackResponse(clientFD: clientFD, status: "404 Not Found", location: nil, body: "Not found")
        return false
    }
    let items = components.queryItems ?? []
    func values(_ name: String) -> [String] { items.filter { $0.name == name }.compactMap(\.value) }
    let states = values("state")
    guard states.count == 1, constantTimeEquals(states[0], expectedState) else {
        writeChatGptLoopbackResponse(
            clientFD: clientFD,
            status: "400 Bad Request",
            location: nil,
            body: "This sign-in link is not the one Episteme started. Return to the app and try again."
        )
        return false
    }
    // Hand the result over before answering: by the time the browser follows
    // the redirect and closes, the app already holds the callback.
    let errors = values("error")
    let codes = values("code")
    let clientIds = values("client_id")
    if let error = errors.first {
        onCallback(.denied(error: error))
    } else if codes.count == 1, !codes[0].isEmpty, clientIds.count <= 1 {
        onCallback(.authorized(code: codes[0], clientId: clientIds.first))
    } else {
        onCallback(.failed(reason: "registration_incomplete"))
    }
    writeChatGptLoopbackResponse(
        clientFD: clientFD,
        status: "302 Found",
        location: completionURL,
        body: "Return to Episteme. You can close this page."
    )
    return true
}

nonisolated private func writeChatGptLoopbackResponse(clientFD: Int32, status: String, location: String?, body: String) {
    let html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Episteme</title></head>" +
        "<body style=\"font:17px -apple-system,sans-serif;margin:20vh auto;max-width:28rem;padding:24px\">" +
        "<p>\(body)</p></body></html>"
    let bodyBytes = Array(html.utf8)
    var head = "HTTP/1.1 \(status)\r\n"
    if let location { head += "Location: \(location)\r\n" }
    head += "Content-Type: text/html; charset=utf-8\r\n"
    head += "Content-Length: \(bodyBytes.count)\r\n"
    head += "Cache-Control: no-store\r\n"
    head += "Referrer-Policy: no-referrer\r\n"
    head += "Connection: close\r\n\r\n"
    let response = Array(head.utf8) + bodyBytes
    var written = 0
    while written < response.count {
        let count = response[written...].withUnsafeBytes { pointer in
            write(clientFD, pointer.baseAddress, pointer.count)
        }
        if count <= 0 { break }
        written += count
    }
}

nonisolated private func constantTimeEquals(_ lhs: String, _ rhs: String) -> Bool {
    let left = Array(lhs.utf8)
    let right = Array(rhs.utf8)
    guard left.count == right.count else { return false }
    var difference: UInt8 = 0
    for index in left.indices {
        difference |= left[index] ^ right[index]
    }
    return difference == 0
}

private extension Array where Element == UInt8 {
    nonisolated func containsSubsequence(_ needle: [UInt8]) -> Bool {
        guard !needle.isEmpty, count >= needle.count else { return false }
        for start in 0...(count - needle.count) where self[start..<(start + needle.count)].elementsEqual(needle) {
            return true
        }
        return false
    }
}
