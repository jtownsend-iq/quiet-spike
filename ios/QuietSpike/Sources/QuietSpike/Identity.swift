// ios/QuietSpike/Sources/QuietSpike/Identity.swift
//
// device_id is a stable per-install UUID stored in the iOS Keychain
// (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) so it survives
// app updates but resets on uninstall — same shape as the Android
// EncryptedSharedPreferences-backed counterpart.
//
// client_seq is a monotonically increasing counter persisted to
// UserDefaults. Sync writes after every increment so kill -9 between
// commit and persistence creates a gap, not a duplicate (duplicates
// break the (device_id, client_seq) idempotency contract in ADR-002).

import Foundation
import Security

enum Identity {
    private static let service = "app.quiet.spike"
    private static let deviceKey = "device_id"
    private static let seqKey = "client_seq"

    private static let lock = NSLock()
    nonisolated(unsafe) private static var cachedDeviceId: String?

    static func deviceId() -> String {
        lock.lock(); defer { lock.unlock() }
        if let id = cachedDeviceId { return id }
        if let id = keychainRead(deviceKey) {
            cachedDeviceId = id
            return id
        }
        let id = UUID().uuidString
        keychainWrite(deviceKey, value: id)
        cachedDeviceId = id
        return id
    }

    static func nextClientSeq() -> Int64 {
        lock.lock(); defer { lock.unlock() }
        let current = (UserDefaults.standard.object(forKey: seqKey) as? Int64) ?? 0
        let next = current + 1
        UserDefaults.standard.set(next, forKey: seqKey)
        UserDefaults.standard.synchronize() // belt-and-braces fsync
        return next
    }

    // ---- Keychain helpers --------------------------------------------------

    private static func keychainRead(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String:        kSecClassGenericPassword,
            kSecAttrService as String:  service,
            kSecAttrAccount as String:  key,
            kSecReturnData as String:   true,
            kSecMatchLimit as String:   kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess,
              let data = item as? Data,
              let str = String(data: data, encoding: .utf8) else { return nil }
        return str
    }

    private static func keychainWrite(_ key: String, value: String) {
        let data = value.data(using: .utf8)!
        let query: [String: Any] = [
            kSecClass as String:       kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        let attrs: [String: Any] = [
            kSecValueData as String:        data,
            kSecAttrAccessible as String:   kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if updateStatus == errSecItemNotFound {
            var add = query
            add.merge(attrs) { _, new in new }
            _ = SecItemAdd(add as CFDictionary, nil)
        }
    }
}
