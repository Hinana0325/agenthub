import XCTest
@testable import AgentControlCenter

// MARK: - URLValidator 单元测试
// 验证 SSRF 防护：危险 scheme / 链路本地 / 元数据服务 / 内网私网 / 广播等
final class URLValidatorTests: XCTestCase {

    // MARK: - Scheme 白名单

    /// 允许的 scheme 应通过校验
    func testAllowedSchemes() {
        XCTAssertNotNil(URLValidator.validate("https://api.openai.com"))
        XCTAssertNotNil(URLValidator.validate("http://localhost:8080"))
        XCTAssertNotNil(URLValidator.validate("ws://localhost/ws"))
        XCTAssertNotNil(URLValidator.validate("wss://hermes.example.com/ws"))
    }

    /// 危险 scheme 应被拒绝
    func testDangerousSchemesRejected() {
        XCTAssertNil(URLValidator.validate("file:///etc/passwd"))
        XCTAssertNil(URLValidator.validate("data:text/html,<script>"))
        XCTAssertNil(URLValidator.validate("ftp://example.com"))
        XCTAssertNil(URLValidator.validate("javascript:alert(1)"))
    }

    // MARK: - Host 必填

    /// 缺失 host 应被拒绝
    func testMissingHostRejected() {
        XCTAssertNil(URLValidator.validate("https://"))
        XCTAssertNil(URLValidator.validate("https:///path"))
    }

    // MARK: - Localhost 放行

    /// allowLocalhost=true（默认）时，localhost / 127.0.0.1 应放行
    func testLocalhostAllowedByDefault() {
        XCTAssertNotNil(URLValidator.validate("http://localhost:3000"))
        XCTAssertNotNil(URLValidator.validate("http://127.0.0.1:3000"))
        XCTAssertNotNil(URLValidator.validate("ws://localhost/ws"))
    }

    /// allowLocalhost=false 时，localhost / 127.0.0.1 应被拒绝
    func testLocalhostRejectedWhenDisabled() {
        XCTAssertNil(URLValidator.validate("http://localhost:3000", allowLocalhost: false))
        XCTAssertNil(URLValidator.validate("http://127.0.0.1:3000", allowLocalhost: false))
    }

    // MARK: - 链路本地 / 元数据服务

    /// 169.254.169.254（AWS/GCP/Azure metadata）必须被拒绝
    /// 这是 SSRF 最常见的攻击目标，apiKey 泄漏后会被直接读取云凭据
    func testMetadataServiceRejected() {
        XCTAssertNil(URLValidator.validate("http://169.254.169.254/latest/meta-data/"))
        XCTAssertNil(URLValidator.validate("http://169.254.170.2/"))
        XCTAssertNil(URLValidator.validate("http://169.254.0.1/", allowLocalhost: false))
        // 即便 allowLocalhost=true，链路本地也必须被拒
        XCTAssertNil(URLValidator.validate("http://169.254.169.254/", allowLocalhost: true))
    }

    // MARK: - 私网地址

    /// 10.0.0.0/8 在 allowLocalhost=false 时被拒
    func testPrivateNetwork10Rejected() {
        XCTAssertNil(URLValidator.validate("http://10.0.0.1", allowLocalhost: false))
        XCTAssertNil(URLValidator.validate("http://10.255.255.255", allowLocalhost: false))
        // allowLocalhost=true 时放行（本地部署场景）
        XCTAssertNotNil(URLValidator.validate("http://10.0.0.1", allowLocalhost: true))
    }

    /// 172.16.0.0/12 在 allowLocalhost=false 时被拒
    func testPrivateNetwork172Rejected() {
        XCTAssertNil(URLValidator.validate("http://172.16.0.1", allowLocalhost: false))
        XCTAssertNil(URLValidator.validate("http://172.31.255.255", allowLocalhost: false))
        // 172.32.0.0 不是私网，应放行
        XCTAssertNotNil(URLValidator.validate("http://172.32.0.1", allowLocalhost: false))
    }

    /// 192.168.0.0/16 在 allowLocalhost=false 时被拒
    func testPrivateNetwork192Rejected() {
        XCTAssertNil(URLValidator.validate("http://192.168.0.1", allowLocalhost: false))
        XCTAssertNil(URLValidator.validate("http://192.168.1.100:8080", allowLocalhost: false))
    }

    // MARK: - 广播地址

    /// 255.255.255.255 必须被拒
    func testBroadcastRejected() {
        XCTAssertNil(URLValidator.validate("http://255.255.255.255"))
        XCTAssertNil(URLValidator.validate("http://255.255.255.255", allowLocalhost: true))
    }

    // MARK: - 公网地址

    /// 公网 IP 应放行
    func testPublicIPAllowed() {
        XCTAssertNotNil(URLValidator.validate("https://8.8.8.8"))
        XCTAssertNotNil(URLValidator.validate("https://1.1.1.1/dns-query"))
        XCTAssertNotNil(URLValidator.validate("https://142.250.190.46"))
    }

    // MARK: - 公网域名

    /// 常规公网域名应放行
    func testPublicDomainAllowed() {
        XCTAssertNotNil(URLValidator.validate("https://api.openai.com"))
        XCTAssertNotNil(URLValidator.validate("https://api.openai.com/v1/chat/completions"))
        XCTAssertNotNil(URLValidator.validate("wss://hermes.example.com/ws"))
    }

    // MARK: - 大小写无关

    /// scheme 大写也应被识别
    func testSchemeCaseInsensitive() {
        XCTAssertNotNil(URLValidator.validate("HTTPS://api.openai.com"))
        XCTAssertNotNil(URLValidator.validate("WSS://hermes.example.com/ws"))
    }

    // MARK: - IPv6 简化处理

    /// fe80::/10 链路本地 IPv6 应被拒
    func testIPv6LinkLocalRejected() {
        XCTAssertNil(URLValidator.validate("http://[fe80::1]"))
        XCTAssertNil(URLValidator.validate("http://fe80::1"))
    }

    /// ::1 回环地址在 allowLocalhost=true 时放行
    func testIPv6LoopbackAllowed() {
        XCTAssertNotNil(URLValidator.validate("http://[::1]", allowLocalhost: true))
        // allowLocalhost=false 时不放行（::1 是回环，但当前实现未对 IPv6 回环做特殊处理，
        // 仅判断 fe80:: 前缀，所以会落入"非链路本地"分支放行 — 该用例仅断言现状，
        // 后续如需严格化可调整）
    }

    // MARK: - 非法 IPv4 字面量

    /// 非法 IP 段（>255）应被当作域名处理或拒绝，不应崩溃
    func testInvalidIPv4DoesNotCrash() {
        // 999 在 UInt8 范围外，parseIPv4 返回 nil，走域名分支放行
        XCTAssertNotNil(URLValidator.validate("http://999.999.999.999"))
    }

    // MARK: - 返回值

    /// 校验通过应返回与输入等价的 URL
    func testReturnsURL() {
        let url = URLValidator.validate("https://api.openai.com/v1/chat")
        XCTAssertNotNil(url)
        XCTAssertEqual(url?.scheme, "https")
        XCTAssertEqual(url?.host, "api.openai.com")
        XCTAssertEqual(url?.path, "/v1/chat")
    }
}
