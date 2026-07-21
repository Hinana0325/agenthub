import Foundation
import Observation

// MARK: - AgentManager
// 对应 Android AgentManager + AgentRegistry

/// Agent 管理器 — Agent Control Center 的核心调度中心。
///
/// 职责：
/// - 维护所有已注册 Agent 的生命周期
/// - 提供 Agent 状态的响应式流
/// - 协调 Transport 层与 Agent 实例的绑定
/// - 按 Capability 查询 Agent
///
/// `@MainActor` 隔离保证 `agents` / `activeAgent` 等响应式状态的读写
/// 均在主线程进行，避免 SwiftUI 视图读取时发生数据竞争。
@MainActor
@Observable
final class AgentManager {

    /// 所有已注册的 Agent
    private(set) var agents: [Agent] = []

    /// 当前活跃的 Agent
    private(set) var activeAgent: Agent?

    /// Agent 能力索引 (capability -> agentId 集合)
    private var capabilityIndex: [AgentCapability: Set<String>] = [:]

    // MARK: - Registration

    /// 注册或更新 Agent
    func register(_ agent: Agent) {
        if let index = agents.firstIndex(where: { $0.id == agent.id }) {
            agents[index] = agent
        } else {
            agents.append(agent)
        }
        // 更新能力索引
        for cap in agent.capabilities {
            capabilityIndex[cap, default: []].insert(agent.id)
        }
    }

    /// 注销 Agent
    func unregister(agentId: String) {
        // 从能力索引中移除
        for (cap, ids) in capabilityIndex {
            var updated = ids
            updated.remove(agentId)
            if updated.isEmpty {
                capabilityIndex.removeValue(forKey: cap)
            } else {
                capabilityIndex[cap] = updated
            }
        }
        agents.removeAll { $0.id == agentId }
        if activeAgent?.id == agentId {
            activeAgent = nil
        }
    }

    /// 设置活跃 Agent
    func setActive(agentId: String) {
        activeAgent = agents.first { $0.id == agentId }
    }

    /// 更新 Agent 状态
    func updateStatus(agentId: String, status: AgentStatus) {
        guard let index = agents.firstIndex(where: { $0.id == agentId }) else { return }
        var updated = agents[index]
        updated.status = status
        agents[index] = updated
    }

    // MARK: - Query

    /// 获取 Agent
    func getAgent(_ agentId: String) -> Agent? {
        agents.first { $0.id == agentId }
    }

    /// 按 Capability 查询 Agent
    func getAgentsByCapability(_ capability: AgentCapability) -> [Agent] {
        let agentIds = capabilityIndex[capability] ?? []
        return agents.filter { agentIds.contains($0.id) }
    }

    /// 获取所有在线 Agent
    var onlineAgents: [Agent] {
        agents.filter { $0.status == .online }
    }
}
