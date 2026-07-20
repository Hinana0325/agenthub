package com.agentcontrolcenter.app.runtime.workflow

import com.agentcontrolcenter.app.agent.model.AgentType
import org.junit.Assert.*
import org.junit.Test

/**
 * WorkflowEngine 数据模型和 TransformType 测试。
 */
class WorkflowEngineTest {

    // ── NodeType ──

    @Test
    fun `all node types have display names`() {
        NodeType.entries.forEach { type ->
            assertTrue("NodeType.${type.name} should have non-empty displayName",
                type.displayName.isNotBlank())
        }
    }

    @Test
    fun `node type display names are correct`() {
        assertEquals("Input", NodeType.INPUT.displayName)
        assertEquals("Agent", NodeType.AGENT.displayName)
        assertEquals("Transform", NodeType.TRANSFORM.displayName)
        assertEquals("Output", NodeType.OUTPUT.displayName)
    }

    // ── TransformType ──

    @Test
    fun `all transform types have display names`() {
        TransformType.entries.forEach { type ->
            assertTrue("TransformType.${type.name} should have non-empty displayName",
                type.displayName.isNotBlank())
        }
    }

    // ── WorkflowNode ──

    @Test
    fun `workflow node default values`() {
        val node = WorkflowNode(type = NodeType.INPUT)
        assertNotNull(node.id)
        assertTrue(node.id.isNotBlank())
        assertEquals(NodeType.INPUT, node.type)
        assertEquals("", node.label)
        assertNull(node.agentType)
        assertEquals("", node.prompt)
        assertEquals(TransformType.PASSTHROUGH, node.transformType)
        assertEquals(0f, node.positionX)
        assertEquals(0f, node.positionY)
        assertEquals("", node.outputCache)
    }

    @Test
    fun `workflow node custom values`() {
        val node = WorkflowNode(
            id = "test-id",
            type = NodeType.AGENT,
            label = "My Agent",
            agentType = com.agentcontrolcenter.app.agent.model.AgentType.OpenAI,
            prompt = "Hello",
            positionX = 100f,
            positionY = 200f
        )
        assertEquals("test-id", node.id)
        assertEquals(NodeType.AGENT, node.type)
        assertEquals("My Agent", node.label)
        assertEquals(com.agentcontrolcenter.app.agent.model.AgentType.OpenAI, node.agentType)
        assertEquals("Hello", node.prompt)
        assertEquals(100f, node.positionX)
        assertEquals(200f, node.positionY)
    }

    // ── WorkflowEdge ──

    @Test
    fun `workflow edge default values`() {
        val edge = WorkflowEdge(fromNodeId = "a", toNodeId = "b")
        assertNotNull(edge.id)
        assertEquals("a", edge.fromNodeId)
        assertEquals("b", edge.toNodeId)
        assertNull(edge.condition)
    }

    @Test
    fun `workflow edge with condition`() {
        val edge = WorkflowEdge(fromNodeId = "a", toNodeId = "b", condition = "contains:ok")
        assertEquals("contains:ok", edge.condition)
    }

    // ── Workflow ──

    @Test
    fun `workflow default values`() {
        val workflow = Workflow(name = "Test", description = "desc")
        assertNotNull(workflow.id)
        assertEquals("Test", workflow.name)
        assertEquals("desc", workflow.description)
        assertTrue(workflow.nodes.isEmpty())
        assertTrue(workflow.edges.isEmpty())
    }

    @Test
    fun `workflow with nodes and edges`() {
        val nodes = listOf(
            WorkflowNode(id = "in", type = NodeType.INPUT, label = "Input"),
            WorkflowNode(id = "out", type = NodeType.OUTPUT, label = "Output")
        )
        val edges = listOf(
            WorkflowEdge(fromNodeId = "in", toNodeId = "out")
        )
        val workflow = Workflow(name = "Simple", description = "", nodes = nodes, edges = edges)
        assertEquals(2, workflow.nodes.size)
        assertEquals(1, workflow.edges.size)
    }

    // ── WorkflowTemplates ──

    @Test
    fun `translation chain template has correct structure`() {
        val workflow = WorkflowTemplates.translationChain()
        assertTrue(workflow.nodes.size >= 3) // input + agent + output
        assertTrue(workflow.edges.size >= 2)
        assertTrue(workflow.nodes.any { it.type == NodeType.INPUT })
        assertTrue(workflow.nodes.any { it.type == NodeType.OUTPUT })
    }

    @Test
    fun `code review template has correct structure`() {
        val workflow = WorkflowTemplates.codeReview()
        assertTrue(workflow.nodes.size >= 2)
        assertTrue(workflow.edges.size >= 1)
    }

    @Test
    fun `research assistant template has correct structure`() {
        val workflow = WorkflowTemplates.researchAssistant()
        assertTrue(workflow.nodes.size >= 2)
        assertTrue(workflow.edges.size >= 1)
    }

    @Test
    fun `all templates have non-empty names`() {
        val templates = WorkflowTemplates.allTemplates()
        assertTrue(templates.isNotEmpty())
        templates.forEach { template ->
            assertTrue("Template should have non-empty name", template.name.isNotBlank())
        }
    }
}
