import XCTest
@testable import AgentControlCenter

// MARK: - MCP 模型单元测试
final class McpModelTests: XCTestCase {

    // MARK: - JsonRpcRequest 测试

    /// 测试 JsonRpcRequest 创建和 JSON 编码（验证 method/params/id 格式）
    func testJsonRpcRequestCreation() {
        let request = JsonRpcRequest(
            id: 1,
            method: "tools/list",
            params: ["includeDeprecated": AnyCodable(false)]
        )
        XCTAssertEqual(request.jsonrpc, "2.0")
        XCTAssertEqual(request.id, 1)
        XCTAssertEqual(request.method, "tools/list")
        XCTAssertNotNil(request.params)
    }

    /// 测试 JsonRpcRequest JSON 编码格式
    func testJsonRpcRequestEncoding() {
        let request = JsonRpcRequest(
            id: 42,
            method: "tools/call",
            params: ["name": AnyCodable("read_file"), "arguments": AnyCodable(["path": "/tmp/test"])]
        )

        let data = try? JSONEncoder().encode(request)
        XCTAssertNotNil(data)

        // 解码为字典验证 JSON 结构
        let json = try? JSONSerialization.jsonObject(with: data!) as? [String: Any]
        XCTAssertNotNil(json)
        XCTAssertEqual(json?["jsonrpc"] as? String, "2.0")
        XCTAssertEqual(json?["id"] as? Int, 42)
        XCTAssertEqual(json?["method"] as? String, "tools/call")
        XCTAssertNotNil(json?["params"])
    }

    /// 测试 JsonRpcRequest 无 params 编码
    func testJsonRpcRequestWithoutParams() {
        let request = JsonRpcRequest(id: 2, method: "initialize")
        let data = try? JSONEncoder().encode(request)
        XCTAssertNotNil(data)

        let json = try? JSONSerialization.jsonObject(with: data!) as? [String: Any]
        XCTAssertNotNil(json)
        XCTAssertEqual(json?["method"] as? String, "initialize")
        // params 为 nil 时不应出现在 JSON 中
        XCTAssertNil(json?["params"])
    }

    // MARK: - JsonRpcResponse 测试

    /// 测试 JsonRpcResponse 成功响应解码
    func testJsonRpcResponseSuccessDecoding() {
        let jsonStr = """
        {
            "jsonrpc": "2.0",
            "id": 1,
            "result": {"tools": [{"name": "test_tool"}]}
        }
        """
        let data = jsonStr.data(using: .utf8)!

        let response = try? JSONDecoder().decode(JsonRpcResponse.self, from: data)
        XCTAssertNotNil(response)
        XCTAssertEqual(response?.jsonrpc, "2.0")
        XCTAssertEqual(response?.id, 1)
        XCTAssertNotNil(response?.result)
        XCTAssertNil(response?.error)
    }

    /// 测试 JsonRpcResponse 错误响应解码
    func testJsonRpcResponseErrorDecoding() {
        let jsonStr = """
        {
            "jsonrpc": "2.0",
            "id": 1,
            "error": {"code": -32600, "message": "Invalid Request"}
        }
        """
        let data = jsonStr.data(using: .utf8)!

        let response = try? JSONDecoder().decode(JsonRpcResponse.self, from: data)
        XCTAssertNotNil(response)
        XCTAssertEqual(response?.id, 1)
        XCTAssertNil(response?.result)
        XCTAssertNotNil(response?.error)
        XCTAssertEqual(response?.error?.code, -32600)
        XCTAssertEqual(response?.error?.message, "Invalid Request")
    }

    // MARK: - McpServer 测试

    /// 测试 McpServer 创建
    func testMcpServerCreation() {
        let server = McpServer(
            id: "srv-1",
            name: "测试服务器",
            transportUrl: "http://localhost:3000/mcp",
            transportType: .sse,
            apiKey: "api-key-123",
            isEnabled: true
        )
        XCTAssertEqual(server.id, "srv-1")
        XCTAssertEqual(server.name, "测试服务器")
        XCTAssertEqual(server.transportUrl, "http://localhost:3000/mcp")
        XCTAssertEqual(server.transportType, .sse)
        XCTAssertEqual(server.apiKey, "api-key-123")
        XCTAssertTrue(server.isEnabled)
    }

    /// 测试 McpServer 默认值
    func testMcpServerDefaults() {
        let server = McpServer(id: "s1", name: "默认", transportUrl: "url")
        XCTAssertEqual(server.transportType, .sse)
        XCTAssertNil(server.apiKey)
        XCTAssertTrue(server.isEnabled)
        // McpServerCapabilities 默认值
        XCTAssertFalse(server.capabilities.tools)
        XCTAssertFalse(server.capabilities.resources)
    }

    // MARK: - McpTool 测试

    /// 测试 McpTool 创建
    func testMcpToolCreation() {
        let schema = McpToolSchema(
            properties: [
                "path": McpToolProperty(type: "string", description: "文件路径")
            ],
            required: ["path"]
        )
        let tool = McpTool(
            name: "read_file",
            description: "读取文件内容",
            inputSchema: schema,
            serverId: "srv-1"
        )
        XCTAssertEqual(tool.name, "read_file")
        XCTAssertEqual(tool.description, "读取文件内容")
        XCTAssertEqual(tool.serverId, "srv-1")
        // id 为 computed property
        XCTAssertEqual(tool.id, "srv-1:read_file")
        XCTAssertEqual(tool.inputSchema.required, ["path"])
    }

    // MARK: - McpContent 测试

    /// 测试 McpContent .text 类型
    func testMcpContentText() {
        let content = McpContent.text("Hello MCP")

        let data = try? JSONEncoder().encode(content)
        XCTAssertNotNil(data)

        let decoded = try? JSONDecoder().decode(McpContent.self, from: data!)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded, content, "编码后解码的 McpContent.text 应与原始值相等")
        if case .text(let text) = decoded {
            XCTAssertEqual(text, "Hello MCP")
        } else {
            XCTFail("应为 .text 类型")
        }
    }

    /// 测试 McpContent .image 类型
    func testMcpContentImage() {
        let content = McpContent.image(data: "base64imagedata", mimeType: "image/png")

        let data = try? JSONEncoder().encode(content)
        XCTAssertNotNil(data)

        let decoded = try? JSONDecoder().decode(McpContent.self, from: data!)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded, content, "编码后解码的 McpContent.image 应与原始值相等")
        if case .image(let imgData, let mime) = decoded {
            XCTAssertEqual(imgData, "base64imagedata")
            XCTAssertEqual(mime, "image/png")
        } else {
            XCTFail("应为 .image 类型")
        }
    }

    // MARK: - AnyCodable 测试

    /// 测试 AnyCodable 字符串访问
    func testAnyCodableAsString() {
        let codable = AnyCodable("hello")
        XCTAssertEqual(codable.asString, "hello")
        XCTAssertNil(codable.asInt)
        XCTAssertNil(codable.asDict)
        XCTAssertNil(codable.asArray)
    }

    /// 测试 AnyCodable 整数访问
    func testAnyCodableAsInt() {
        let codable = AnyCodable(42)
        XCTAssertEqual(codable.asInt, 42)
        XCTAssertNil(codable.asString)
    }

    /// 测试 AnyCodable 字典访问
    func testAnyCodableAsDict() {
        let dict: [String: Any] = ["key": "value", "num": 123]
        let codable = AnyCodable(dict)
        XCTAssertNotNil(codable.asDict)
        XCTAssertEqual(codable.asDict?["key"] as? String, "value")
        XCTAssertNil(codable.asString)
        XCTAssertNil(codable.asArray)
    }

    /// 测试 AnyCodable 数组访问
    func testAnyCodableAsArray() {
        let array: [Any] = [1, 2, 3]
        let codable = AnyCodable(array)
        XCTAssertNotNil(codable.asArray)
        XCTAssertEqual(codable.asArray?.count, 3)
        XCTAssertNil(codable.asString)
        XCTAssertNil(codable.asDict)
    }

    /// 测试 AnyCodable 编解码
    func testAnyCodableCodable() {
        // 字符串编解码
        let strCodable = AnyCodable("test")
        let strData = try? JSONEncoder().encode(strCodable)
        XCTAssertNotNil(strData)
        let strDecoded = try? JSONDecoder().decode(AnyCodable.self, from: strData!)
        XCTAssertEqual(strDecoded?.asString, "test")

        // 整数编解码
        let intCodable = AnyCodable(99)
        let intData = try? JSONEncoder().encode(intCodable)
        XCTAssertNotNil(intData)
        let intDecoded = try? JSONDecoder().decode(AnyCodable.self, from: intData!)
        XCTAssertEqual(intDecoded?.asInt, 99)

        // 布尔值编解码
        let boolCodable = AnyCodable(true)
        let boolData = try? JSONEncoder().encode(boolCodable)
        XCTAssertNotNil(boolData)
        let boolDecoded = try? JSONDecoder().decode(AnyCodable.self, from: boolData!)
        XCTAssertEqual(boolDecoded?.asBool, true)
    }

    // MARK: - McpToolResult 测试

    /// 测试 McpToolResult asText 提取
    func testMcpToolResultAsText() {
        let result = McpToolResult(
            content: [
                .text("第一行"),
                .text("第二行"),
                .image(data: "img", mimeType: "image/png")
            ],
            isError: false
        )
        XCTAssertEqual(result.asText, "第一行\n第二行")
        XCTAssertFalse(result.isError)
    }
}