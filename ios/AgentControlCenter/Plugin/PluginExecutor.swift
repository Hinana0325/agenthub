import Foundation

// MARK: - PluginExecutor
// 对应 Android PluginExecutor (Phase 5.1)

/// 插件执行引擎。根据 PluginAction 执行具体动作。
///
/// 三种动作类型：
/// - HttpCall: 通过 URLSession 发起 HTTP 请求，{query} 替换为用户输入
/// - Broadcast: iOS 上降级为通知发送 (Android 的 Intent Broadcast 在 iOS 不适用)
/// - Workflow: 产出提示词，交由 Agent 执行 (sendToAgent = true)
final class PluginExecutor {

    private let session: URLSession

    /// F19: 插件 HTTP 请求禁止设置的敏感 header 黑名单（小写匹配）。
    /// 参考 fetch 标准的 forbidden header names + OAuth/安全相关头。
    private static let blockedHeaderFields: Set<String> = [
        "authorization", "proxy-authorization",
        "cookie", "set-cookie", "cookie2", "set-cookie2",
        "host", "content-length", "connection",
        "upgrade", "transfer-encoding", "te",
        "trailer", "expect",
        "www-authenticate", "proxy-authenticate",
        "x-api-key", "x-auth-token", "x-session-token"
    ]

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        self.session = URLSession(configuration: config)
    }

    // MARK: - Execute

    /// 执行插件动作
    /// - Parameters:
    ///   - action: 插件动作定义
    ///   - input: 用户输入 (替换 {query} 占位符)
    /// - Returns: 执行结果
    func execute(_ action: PluginAction, input: String) async -> PluginResult {
        switch action {
        case .httpCall(let http):
            return await executeHttp(http, input: input)
        case .broadcast(let broadcast):
            return await executeBroadcast(broadcast, input: input)
        case .workflow(let workflow):
            return executeWorkflow(workflow, input: input)
        }
    }

    // MARK: - HTTP Call

    /// HTTP 请求执行。
    /// {query} 在 URL 中用 URL 编码替换，在 bodyTemplate 中用原始值替换。
    /// 响应截断为 3000 字符。
    private func executeHttp(_ http: PluginAction.HttpCall, input: String) async -> PluginResult {
        let encodedInput = input.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? input
        let urlString = http.url.replacingOccurrences(of: "{query}", with: encodedInput)

        // H-S3 修复：原代码用 URL(string:) 直接构造，未调用 URLValidator.validate，
        // 恶意插件可设置 url=http://169.254.169.254/... 触发 SSRF 并可覆盖 Authorization
        // 等 header。改为过 URLValidator（插件不应访问本地服务，allowLocalhost=false）。
        guard let url = URLValidator.validate(urlString, allowLocalhost: false) else {
            return PluginResult(content: "Error: URL not allowed (blocked by URLValidator)")
        }

        var request = URLRequest(url: url)
        request.httpMethod = http.method

        // F19 修复：header key 黑名单过滤。
        // 插件 headers 来自用户/marketplace 配置，若不限制可注入 Authorization / Cookie /
        // Host / Set-Cookie 等敏感头，冒充用户凭据或绕过同源策略。仅允许安全的自定义头。
        for (key, value) in http.headers {
            let lowercasedKey = key.lowercased()
            if Self.blockedHeaderFields.contains(lowercasedKey) {
                continue
            }
            request.setValue(value.replacingOccurrences(of: "{query}", with: input), forHTTPHeaderField: key)
        }

        // POST 等需要 body 的方法
        if http.method != "GET" && http.method != "HEAD" {
            if let bodyTemplate = http.bodyTemplate {
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = bodyTemplate
                    .replacingOccurrences(of: "{query}", with: input)
                    .data(using: .utf8)
            }
        }

        do {
            let (data, response) = try await session.data(for: request)
            let httpResponse = response as? HTTPURLResponse
            let statusCode = httpResponse?.statusCode ?? 0
            var responseText = String(data: data, encoding: .utf8) ?? ""
            // 截断为 3000 字符
            if responseText.count > 3000 {
                responseText = String(responseText.prefix(3000))
            }
            return PluginResult(content: "HTTP \(statusCode)\n\(responseText)")
        } catch {
            return PluginResult(content: "HTTP Error: \(error.localizedDescription)")
        }
    }

    // MARK: - Broadcast

    /// 广播执行。
    /// iOS 上没有 Android 的 Intent Broadcast 机制，降级为本地通知。
    private func executeBroadcast(_ broadcast: PluginAction.Broadcast, input: String) async -> PluginResult {
        // iOS 替代方案：通过 NotificationCenter 发送应用内通知
        var userInfo: [String: Any] = [:]
        for (key, value) in broadcast.extras {
            userInfo[key] = value.replacingOccurrences(of: "{query}", with: input)
        }
        await MainActor.run {
            NotificationCenter.default.post(
                name: NSNotification.Name(broadcast.action),
                object: nil,
                userInfo: userInfo
            )
        }
        return PluginResult(content: "Broadcast sent: \(broadcast.action)")
    }

    // MARK: - Workflow

    /// Workflow 动作。产出提示词，返回 sendToAgent = true 的结果。
    private func executeWorkflow(_ workflow: PluginAction.WorkflowAction, input: String) -> PluginResult {
        let prompt = workflow.promptTemplate.replacingOccurrences(of: "{query}", with: input)
        return PluginResult(content: prompt, sendToAgent: true)
    }
}
