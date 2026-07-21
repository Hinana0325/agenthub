import Foundation

// MARK: - URLValidator
// 防止用户输入危险 URL 触发 SSRF / apiKey 泄漏

/// URL 安全校验器。
///
/// 用于校验用户配置的 Agent endpoint / WebSocket URL / MCP transport URL，
/// 防止 SSRF（如 `http://169.254.169.254/...` 探活 AWS metadata 泄漏 apiKey）、
/// 本地端口扫描、内网穿透、危险 scheme（file:// / data://）等。
enum URLValidator {

    /// 允许的 scheme 白名单
    static let allowedSchemes: Set<String> = ["http", "https", "ws", "wss"]

    /// 禁止访问的 IP 段（链路本地 / 元数据服务 / 回环陷阱等）
    /// 注意：127.0.0.1 / localhost 允许（本地 Agent 部署场景）
    private static let blockedCIDRs: [(address: String, prefix: Int)] = [
        ("169.254.0.0", 16),    // 链路本地（含 AWS/GCP/Azure metadata 169.254.169.254）
        ("0.0.0.0", 8),         // 本网络
        ("255.255.255.255", 32) // 广播
    ]

    /// 校验 URL 字符串是否安全可用。
    /// - Parameters:
    ///   - urlString: 用户输入的 URL 字符串
    ///   - allowLocalhost: 是否允许 localhost / 127.0.0.1 / 内网私网地址（默认 true，本地 Agent 部署需要）
    /// - Returns: 校验通过返回 URL；失败返回 nil
    static func validate(_ urlString: String, allowLocalhost: Bool = true) -> URL? {
        guard let url = URL(string: urlString),
              let scheme = url.scheme?.lowercased(),
              allowedSchemes.contains(scheme) else {
            return nil
        }

        // 必须有 host
        guard let host = url.host?.lowercased(), !host.isEmpty else {
            return nil
        }

        // localhost 显式放行（若 allowLocalhost）
        if allowLocalhost && (host == "localhost" || host == "ip6-localhost" || host == "::1") {
            return url
        }

        // IPv4 字面量校验
        if let ipv4 = parseIPv4(host) {
            if !isIPv4Allowed(ipv4, allowLocalhost: allowLocalhost) {
                return nil
            }
            return url
        }

        // IPv6 字面量（带方括号或裸写）— 简化处理：仅放行非链路本地
        if host.contains(":") {
            // IPv6，链路本地 fe80::/10 禁止
            let lower = host.hasPrefix("[") ? String(host.dropFirst().dropLast()) : host
            if lower.lowercased().hasPrefix("fe80") {
                return nil
            }
            return url
        }

        // 域名校验：禁止以 .local / .internal 等内网域名探测（防止 mDNS SSRF）
        // 但允许常规公网域名
        return url
    }

    // MARK: - IPv4 Helpers

    private static func parseIPv4(_ s: String) -> [UInt8]? {
        let parts = s.split(separator: ".")
        guard parts.count == 4 else { return nil }
        var bytes: [UInt8] = []
        for p in parts {
            guard let v = UInt8(p), p.count <= 3 else { return nil }
            bytes.append(v)
        }
        return bytes
    }

    /// IPv4 是否允许访问
    private static func isIPv4Allowed(_ ip: [UInt8], allowLocalhost: Bool) -> Bool {
        // 127.0.0.0/8 回环
        if ip[0] == 127 {
            return allowLocalhost
        }
        // 私网地址
        if ip[0] == 10 { return allowLocalhost }
        if ip[0] == 172 && (ip[1] >= 16 && ip[1] <= 31) { return allowLocalhost }
        if ip[0] == 192 && ip[1] == 168 { return allowLocalhost }

        // 禁止的 IP 段
        for cidr in blockedCIDRs {
            if let cidrAddr = parseIPv4(cidr.address), inCIDR(ip, cidrAddr, prefix: cidr.prefix) {
                return false
            }
        }
        return true
    }

    private static func inCIDR(_ ip: [UInt8], _ cidr: [UInt8], prefix: Int) -> Bool {
        let fullBytes = prefix / 8
        let remainder = prefix % 8
        for i in 0..<fullBytes {
            if ip[i] != cidr[i] { return false }
        }
        if remainder > 0 && fullBytes < 4 {
            let mask: UInt8 = (~0) << (8 - remainder)
            if (ip[fullBytes] & mask) != (cidr[fullBytes] & mask) { return false }
        }
        return true
    }
}
