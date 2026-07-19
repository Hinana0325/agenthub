package com.agenthub.app.mcp.bridge

/**
 * MCP 桥接器 — 将 MCP 工具暴露为 Agent 可调用的接口。
 *
 * 桥接流程：
 * 1. Agent 发起工具调用请求
 * 2. McpBridge 路由到对应的 MCP Server
 * 3. MCP Server 执行工具并返回结果
 * 4. McpBridge 将结果返回给 Agent
 */
class McpBridge {
    // TODO: 实现 MCP 工具调用桥接
}
