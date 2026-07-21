import Foundation
import Observation

/// 更新管理器 — 通过 GitHub Release API 检查应用版本更新
/// 对应 Android UpdateManager
///
/// iOS 端仅检查版本号，不执行 APK 下载（App Store 负责分发）。
/// 检测到新版本后，UI 层可引导用户前往 App Store 或 GitHub Release 页面。
@MainActor
@Observable
final class UpdateManager {

    // MARK: - GitHub Release 数据模型

    /// GitHub Release API 返回的数据结构
    struct GitHubRelease: Codable {
        /// 版本标签（如 "v2.3.0"）
        let tagName: String
        /// 发布说明（Markdown 格式）
        let body: String
        /// Release 页面链接
        let htmlUrl: String

        enum CodingKeys: String, CodingKey {
            case tagName = "tag_name"
            case body
            case htmlUrl = "html_url"
        }
    }

    // MARK: - 属性

    /// 是否发现新版本
    var isNewVersionAvailable: Bool = false

    /// 最新版本号（如 "2.3.0"）
    var latestVersion: String?

    /// 发布说明（Markdown）
    var releaseNotes: String?

    /// Release 页面 URL
    var releaseUrl: String?

    /// 是否正在检查更新
    var isChecking: Bool = false

    /// 错误信息
    var error: String?

    // MARK: - 常量

    /// GitHub 仓库路径
    private static let repoOwner = "Hinana0325"
    private static let repoName = "Agent-Control-Center"

    /// 当前应用版本。
    ///
    /// 优先从 Bundle 读取 CFBundleShortVersionString；
    /// 若读取失败则使用硬编码的兜底值。
    ///
    /// CI-fix: 原 `private lazy var ... = { ... }()` 与 `@Observable` 宏不兼容 ——
    /// `@Observable` 宏在转换存储属性时会注入 observation 追踪逻辑，
    /// `lazy` 存储属性的延迟初始化语义与宏注入冲突，编译报
    /// "'lazy' cannot be used on a computed property"。改为计算属性，
    /// `Bundle.main` 读取开销极小（纳秒级），无需缓存。
    private var currentVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "2.2.0"
    }

    // MARK: - 公开接口

    /// 检查 GitHub Release 最新版本。
    ///
    /// 流程：
    /// 1. GET 请求 GitHub Releases API（latest）
    /// 2. 解析 tag_name，去除 "v" 前缀
    /// 3. 按语义化版本号逐段比较（按 "." 分割为数字数组）
    /// 4. 若远端版本更高，更新状态属性
    ///
    /// 此方法会重置所有状态属性，适合重复调用。
    func checkForUpdate() async {
        // 重置状态
        isNewVersionAvailable = false
        latestVersion = nil
        releaseNotes = nil
        releaseUrl = nil
        error = nil
        isChecking = true

        defer { isChecking = false }

        // 构建 API URL
        let urlString = "https://api.github.com/repos/\(Self.repoOwner)/\(Self.repoName)/releases/latest"
        guard let url = URL(string: urlString) else {
            error = "无效的 API URL"
            return
        }

        // 发起请求
        var request = URLRequest(url: url)
        request.setValue("application/vnd.github.v3+json", forHTTPHeaderField: "Accept")
        // GitHub 公开 API 无需认证即可获取 Release 信息
        // 但建议设置 User-Agent 以避免被限流
        request.setValue("AgentControlCenter-iOS", forHTTPHeaderField: "User-Agent")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            // 检查 HTTP 状态码
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 200 {
                error = "GitHub API 返回状态码 \(httpResponse.statusCode)"
                return
            }

            // 解码 JSON
            let decoder = JSONDecoder()
            let release = try decoder.decode(GitHubRelease.self, from: data)

            // 解析版本号（去除 "v" 前缀）
            let remoteVersion = release.tagName.hasPrefix("v")
                ? String(release.tagName.dropFirst())
                : release.tagName

            latestVersion = remoteVersion
            releaseNotes = release.body
            releaseUrl = release.htmlUrl

            // 比较版本号
            isNewVersionAvailable = compareVersions(remoteVersion, currentVersion) == .remoteNewer

        } catch let decodingError as DecodingError {
            error = "解析 Release 数据失败: \(decodingError.localizedDescription)"
        } catch {
            error = "网络请求失败: \(error.localizedDescription)"
        }
    }

    // MARK: - 私有方法

    /// 比较两个语义化版本号。
    ///
    /// 将版本号按 "." 分割为整数数组，逐段比较。
    /// 支持不同长度的版本号（如 "2.3" vs "2.3.0"）。
    ///
    /// - Parameters:
    ///   - remote: 远端版本号
    ///   - local: 本地版本号
    /// - Returns: 比较结果
    private func compareVersions(_ remote: String, _ local: String) -> VersionComparison {
        let remoteParts = remote.split(separator: ".").compactMap { Int($0) }
        let localParts = local.split(separator: ".").compactMap { Int($0) }

        let maxCount = max(remoteParts.count, localParts.count)

        for i in 0..<maxCount {
            let r = i < remoteParts.count ? remoteParts[i] : 0
            let l = i < localParts.count ? localParts[i] : 0

            if r > l { return .remoteNewer }
            if r < l { return .localNewer }
        }

        return .equal
    }

    /// 版本比较结果
    private enum VersionComparison {
        case remoteNewer   // 远端版本更新
        case localNewer    // 本地版本更新（开发版）
        case equal         // 版本相同
    }
}
