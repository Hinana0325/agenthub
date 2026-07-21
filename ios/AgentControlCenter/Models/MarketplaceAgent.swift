import Foundation

// MARK: - MarketplaceAgent
// 对应 Android com.agentcontrolcenter.app.data.model.MarketplaceAgent
//
// 市场展示用的 Agent 模型。与本地 `AgentConfig` 不同，该模型包含市场元信息
// （作者、评分、下载量、官方标识等），用于在 MarketplaceView 中展示。
// 通过 `MarketplaceClient.install(agent:)` 转换为本地 `AgentConfig` 后入库。

/// 市场 Agent 模型 — 用于在市场页面展示可安装的 Agent 信息
struct MarketplaceAgent: Identifiable, Codable, Hashable, Sendable {
    /// 唯一标识（与本地 AgentConfig.id 复用，便于去重）
    let id: String
    /// 显示名称
    let name: String
    /// 描述文本（市场页展示用）
    let description: String
    /// 作者
    let author: String
    /// 所属分类（对应 `MarketplaceCategory.rawValue`）
    let category: String
    /// 图标 URL（可选，本地示例数据中通常为 nil）
    let iconUrl: String?
    /// 下载量
    let downloadCount: Int
    /// 评分（0 ~ 5）
    let rating: Double
    /// 服务器地址
    let serverUrl: String
    /// 能力标签列表
    let capabilities: [String]
    /// 是否为官方发布
    let isOfficial: Bool
    /// 版本号
    let version: String
}

// MARK: - MarketplaceCategory

/// 市场分类枚举
///
/// 用于市场页面的 FilterChip 横向筛选。`rawValue` 与 `MarketplaceAgent.category` 字段保持一致，
/// 同时通过 `displayName` 提供中文展示名，避免将本地化字符串作为 rawValue。
enum MarketplaceCategory: String, CaseIterable, Identifiable {
    case all = "all"
    case productivity = "productivity"
    case development = "development"
    case writing = "writing"
    case assistant = "assistant"
    case creative = "creative"
    case dataAnalysis = "data_analysis"
    case automation = "automation"

    var id: String { rawValue }

    /// 中文展示名
    var displayName: String {
        switch self {
        case .all:          "全部"
        case .productivity:  "效率"
        case .development:   "开发"
        case .writing:       "写作"
        case .assistant:     "助手"
        case .creative:      "创意"
        case .dataAnalysis:  "数据分析"
        case .automation:    "自动化"
        }
    }

    /// SF Symbol 图标名
    var systemImage: String {
        switch self {
        case .all:          "square.grid.2x2"
        case .productivity:  "bolt.fill"
        case .development:   "hammer"
        case .writing:       "pencil.line"
        case .assistant:     "sparkles"
        case .creative:      "paintbrush"
        case .dataAnalysis:  "chart.bar.doc.horizontal"
        case .automation:    "gearshape.2"
        }
    }
}

// MARK: - 示例数据

/// 市场 Agent 本地示例数据（模拟 API 响应）
///
/// 不依赖真实网络请求，所有 Agent 通过该静态数组提供。
/// 字段与 Android `MarketplaceClient.fetchAll()` 的返回结构对齐。
enum MarketplaceSamples {

    /// 全部分类示例
    static let agents: [MarketplaceAgent] = [
        MarketplaceAgent(
            id: "mkt-hermes-pro",
            name: "Hermes Pro",
            description: "通用对话型 Agent，支持长上下文、流式回复、Markdown 渲染与多轮工具调用。适合日常问答、知识查询与轻量任务执行。",
            author: "AgentHub 官方",
            category: MarketplaceCategory.assistant.rawValue,
            iconUrl: nil,
            downloadCount: 12840,
            rating: 4.7,
            serverUrl: "wss://hermes.example.com/ws",
            capabilities: ["对话", "任务", "工作流", "MCP"],
            isOfficial: true,
            version: "2.4.1"
        ),
        MarketplaceAgent(
            id: "mkt-code-pilot",
            name: "Code Pilot",
            description: "面向开发者的代码助手，支持 20+ 编程语言的语法补全、重构建议、单元测试生成与代码评审。",
            author: "DevTools Lab",
            category: MarketplaceCategory.development.rawValue,
            iconUrl: nil,
            downloadCount: 8642,
            rating: 4.6,
            serverUrl: "wss://codepilot.example.com/ws",
            capabilities: ["代码执行", "文件系统", "终端"],
            isOfficial: false,
            version: "1.8.0"
        ),
        MarketplaceAgent(
            id: "mkt-write-assist",
            name: "Write Assist",
            description: "专注中文写作的助手，提供润色、改写、摘要、扩写与多风格切换能力，适配公众号、报告与小说场景。",
            author: "Ink Studio",
            category: MarketplaceCategory.writing.rawValue,
            iconUrl: nil,
            downloadCount: 5120,
            rating: 4.4,
            serverUrl: "wss://write.example.com/ws",
            capabilities: ["对话"],
            isOfficial: false,
            version: "0.9.2"
        ),
        MarketplaceAgent(
            id: "mkt-data-scout",
            name: "Data Scout",
            description: "数据分析型 Agent，支持 CSV/JSON 解析、可视化建议、SQL 生成与多维度透视。搭配文件系统权限可直接读取本地数据。",
            author: "AgentHub 官方",
            category: MarketplaceCategory.dataAnalysis.rawValue,
            iconUrl: nil,
            downloadCount: 3270,
            rating: 4.5,
            serverUrl: "wss://data.example.com/ws",
            capabilities: ["代码执行", "文件系统", "MCP"],
            isOfficial: true,
            version: "1.2.0"
        ),
        MarketplaceAgent(
            id: "mkt-flow-bot",
            name: "Flow Bot",
            description: "自动化流程编排 Agent，配合工作流引擎可串联多个工具完成定时任务、批处理与跨系统联动。",
            author: "AutoOps",
            category: MarketplaceCategory.automation.rawValue,
            iconUrl: nil,
            downloadCount: 2190,
            rating: 4.3,
            serverUrl: "wss://flow.example.com/ws",
            capabilities: ["工作流", "终端", "MCP"],
            isOfficial: false,
            version: "3.0.0"
        ),
        MarketplaceAgent(
            id: "mkt-image-creator",
            name: "Image Creator",
            description: "图像生成 Agent，支持文生图、图生图与多分辨率输出，可通过 MCP 接入多种模型后端。",
            author: "Pixel Forge",
            category: MarketplaceCategory.creative.rawValue,
            iconUrl: nil,
            downloadCount: 6780,
            rating: 4.2,
            serverUrl: "wss://image.example.com/ws",
            capabilities: ["图像生成", "MCP"],
            isOfficial: false,
            version: "1.5.3"
        ),
        MarketplaceAgent(
            id: "mkt-meeting-minutes",
            name: "Meeting Minutes",
            description: "会议纪要 Agent，结合语音输入自动整理会议要点、待办与决议，并支持导出为 Markdown 文档。",
            author: "AgentHub 官方",
            category: MarketplaceCategory.productivity.rawValue,
            iconUrl: nil,
            downloadCount: 4320,
            rating: 4.8,
            serverUrl: "wss://minutes.example.com/ws",
            capabilities: ["对话", "语音", "工作流"],
            isOfficial: true,
            version: "2.1.0"
        ),
        MarketplaceAgent(
            id: "mkt-terminal-agent",
            name: "Terminal Agent",
            description: "终端操作 Agent，提供受限的 Shell 执行能力，支持长任务追踪与命令历史回放。需在设置中显式开启终端权限。",
            author: "Shell Ops",
            category: MarketplaceCategory.development.rawValue,
            iconUrl: nil,
            downloadCount: 1890,
            rating: 4.1,
            serverUrl: "wss://terminal.example.com/ws",
            capabilities: ["终端", "文件系统", "代码执行"],
            isOfficial: false,
            version: "0.7.4"
        )
    ]
}
