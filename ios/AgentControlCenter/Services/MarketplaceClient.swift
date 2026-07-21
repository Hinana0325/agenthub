import Foundation
import Observation

// MARK: - MarketplaceClient
// 对应 Android com.agentcontrolcenter.app.data.marketplace.MarketplaceClient
//
// iOS 版本不依赖真实网络请求，所有数据来自本地示例数组（`MarketplaceSamples.agents`），
// 通过 `loadAgents()` 模拟 API 拉取流程，便于在无网络环境下也能预览市场页面。
// 安装流程将市场 Agent 转换为本地 `AgentConfig`，由调用方（MarketplaceView）配合
// `DataController.saveAgentConfig` 与 `AgentManager.register` 完成入库。

/// 市场客户端 — 加载、搜索、安装市场 Agent
@MainActor
@Observable
final class MarketplaceClient {

    /// 当前加载到的全部 Agent（模拟 API 响应）
    private(set) var agents: [MarketplaceAgent] = []

    /// 已安装的市场 Agent ID 集合（避免重复安装）
    private(set) var installedIds: Set<String> = []

    /// 是否正在加载
    private(set) var isLoading: Bool = false

    /// 错误信息（加载失败时填充）
    private(set) var errorMessage: String?

    /// 全部分类（含 "全部"）
    var categories: [MarketplaceCategory] {
        MarketplaceCategory.allCases
    }

    /// 已安装的 Agent ID 数组（用于 UI 展示）
    var installedIdList: [String] {
        Array(installedIds)
    }

    // MARK: - 加载

    /// 加载市场数据（模拟 API 请求）
    ///
    /// - Parameter query: 可选搜索关键字（nil 表示全量加载）
    func loadAgents(query: String? = nil) async {
        isLoading = true
        errorMessage = nil

        // 模拟网络延迟 300ms
        try? await Task.sleep(nanoseconds: 300_000_000)

        // 模拟 API 调用：从本地示例数据中过滤
        let keyword = query?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        if keyword.isEmpty {
            agents = MarketplaceSamples.agents
        } else {
            agents = MarketplaceSamples.agents.filter { agent in
                agent.name.lowercased().contains(keyword) ||
                agent.description.lowercased().contains(keyword) ||
                agent.author.lowercased().contains(keyword) ||
                agent.capabilities.joined(separator: " ").lowercased().contains(keyword)
            }
        }

        isLoading = false
    }

    // MARK: - 搜索

    /// 在内存中按关键字搜索（同步）
    ///
    /// - Parameter query: 搜索关键字
    /// - Returns: 匹配的 Agent 列表
    func search(query: String) -> [MarketplaceAgent] {
        let keyword = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !keyword.isEmpty else { return agents }

        return agents.filter { agent in
            agent.name.lowercased().contains(keyword) ||
            agent.description.lowercased().contains(keyword) ||
            agent.author.lowercased().contains(keyword) ||
            agent.capabilities.joined(separator: " ").lowercased().contains(keyword)
        }
    }

    /// 按分类过滤
    ///
    /// - Parameter category: 分类（`MarketplaceCategory.all` 表示不过滤）
    /// - Returns: 过滤后的 Agent 列表
    func filter(by category: MarketplaceCategory) -> [MarketplaceAgent] {
        guard category != .all else { return agents }
        return agents.filter { $0.category == category.rawValue }
    }

    // MARK: - 安装

    /// 将市场 Agent 转换为本地 `AgentConfig` 并标记为已安装
    ///
    /// 转换规则：
    /// - `id` 复用市场 Agent 的 id，便于去重
    /// - `name` / `serverUrl` / `apiKey`（空）/ `model`（空） / `systemPrompt`（空） 直接映射
    /// - `type` 默认 `.hermes`（市场 Agent 未携带协议类型，统一按通用 Agent 处理）
    /// - `temperature` / `maxTokens` 使用默认值
    ///
    /// 市场展示用的 `capabilities` 字符串标签不直接写入 `AgentConfig`
    /// （`AgentConfig` 不携带能力字段），调用方在构造运行时 `Agent` 时
    /// 可通过 `capabilityMap` 将中文标签转换为 `AgentCapability` 枚举。
    ///
    /// 调用方拿到返回值后需自行调用 `DataController.saveAgentConfig` 与
    /// `AgentManager.register` 完成持久化与运行时注册。
    ///
    /// - Parameter agent: 市场 Agent
    /// - Returns: 转换后的本地 `AgentConfig`
    /// - Throws: `MarketplaceError.alreadyInstalled` 当已安装时抛出
    func install(agent: MarketplaceAgent) async throws -> AgentConfig {
        // 模拟安装耗时
        try? await Task.sleep(nanoseconds: 500_000_000)

        guard !installedIds.contains(agent.id) else {
            throw MarketplaceError.alreadyInstalled
        }

        let config = AgentConfig(
            id: agent.id,
            name: agent.name,
            type: .hermes,
            serverUrl: agent.serverUrl,
            apiKey: "",
            model: "",
            systemPrompt: "",
            temperature: 0.7,
            maxTokens: 4096
        )

        // 标记为已安装；调用方负责实际入库
        installedIds.insert(agent.id)
        return config
    }

    /// 将市场能力标签（中文/英文）映射为 `AgentCapability` 枚举
    ///
    /// 供调用方在构造运行时 `Agent` 时使用。无法识别的标签会被忽略。
    /// - Parameter labels: 能力标签列表（如 ["对话", "任务"]）
    /// - Returns: 映射后的能力枚举列表
    func capabilityMap(for labels: [String]) -> [AgentCapability] {
        labels.compactMap { label in
            switch label {
            case "对话", "CHAT":       return .chat
            case "任务", "TASK":       return .task
            case "工作流", "WORKFLOW": return .workflow
            case "MCP":               return .mcp
            case "文件系统", "FILESYSTEM": return .filesystem
            case "终端", "TERMINAL":   return .terminal
            case "语音", "VOICE":     return .voice
            case "图像生成", "IMAGE_GEN": return .imageGen
            case "代码执行", "CODE_EXECUTION": return .codeExecution
            default:                   return nil
            }
        }
    }

    /// 标记指定 Agent 为已安装（用于从本地已存在配置恢复安装状态）
    /// - Parameter id: Agent ID
    func markInstalled(id: String) {
        installedIds.insert(id)
    }

    /// 卸载（仅清除安装标记，不删除本地 AgentConfig；本地数据由 Agent 管理页负责）
    /// - Parameter id: Agent ID
    func uninstall(id: String) {
        installedIds.remove(id)
    }

    /// 是否已安装
    /// - Parameter id: Agent ID
    /// - Returns: 是否已安装
    func isInstalled(_ id: String) -> Bool {
        installedIds.contains(id)
    }
}

// MARK: - MarketplaceError

/// 市场相关错误
enum MarketplaceError: LocalizedError {
    case alreadyInstalled
    case installFailed(String)

    var errorDescription: String? {
        switch self {
        case .alreadyInstalled:     return "该 Agent 已安装"
        case .installFailed(let m): return "安装失败：\(m)"
        }
    }
}
