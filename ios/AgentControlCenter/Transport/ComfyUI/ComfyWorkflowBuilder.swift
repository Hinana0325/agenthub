import Foundation

// MARK: - ComfyWorkflowBuilder
// 对应 Android com.agentcontrolcenter.app.transport.comfyui.ComfyWorkflowBuilder
//
// 构造 ComfyUI 工作流 JSON。ComfyUI 工作流格式：节点 ID 为键，
// 值为 {class_type, inputs} 对象。节点间引用为 ["<node_id>", <output_index>] 数组。

/// 构造 ComfyUI 默认文生图工作流。
///
/// 节点结构（与 ComfyUI 默认导出保持一致）：
/// - "3": KSampler（采样器）
/// - "4": CheckpointLoaderSimple（模型加载）
/// - "5": EmptyLatentImage（空 latent）
/// - "6": CLIPTextEncode positive（正向提示词）
/// - "7": CLIPTextEncode negative（负向提示词）
/// - "8": VAEDecode（VAE 解码）
/// - "9": SaveImage（保存图片）
///
/// AgentConfig 字段语义复用：
/// - `model` → checkpoint 文件名（默认 "v1-5-pruned-emaonly.safetensors"）
/// - `temperature` → cfg scale（默认 7.0）
/// - `maxTokens` → steps 采样步数（默认 20）
/// - `systemPrompt` → 负向提示词（默认 "bad quality, blurry"）
enum ComfyWorkflowBuilder {

    // MARK: - Defaults

    private static let defaultCheckpoint = "v1-5-pruned-emaonly.safetensors"
    private static let defaultCfg: Double = 7.0
    private static let defaultSteps = 20
    private static let defaultNegative = "bad quality, blurry"
    private static let defaultWidth = 512
    private static let defaultHeight = 512
    private static let defaultBatchSize = 1
    private static let defaultSampler = "euler"
    private static let defaultScheduler = "normal"

    // MARK: - Node IDs

    private static let nodeKSampler = "3"
    private static let nodeCheckpointLoader = "4"
    private static let nodeEmptyLatent = "5"
    private static let nodePositivePrompt = "6"
    private static let nodeNegativePrompt = "7"
    private static let nodeVaeDecode = "8"
    private static let nodeSaveImage = "9"

    // MARK: - Public

    /// 构造默认文生图工作流，返回 `[String: Any]` 字典（可直接 JSONSerialization 序列化）。
    ///
    /// - Parameters:
    ///   - prompt: 用户输入的正向提示词
    ///   - config: Agent 配置（model/temperature/maxTokens/systemPrompt 复用语义）
    /// - Returns: ComfyUI API 格式的工作流字典
    static func buildTextToImageWorkflow(prompt: String, config: AgentConfig) -> [String: Any] {
        let checkpoint = config.model.isEmpty ? defaultCheckpoint : config.model
        // AgentConfig.temperature 为 Float；> 0 时使用用户值，否则默认
        let cfg: Double = config.temperature > 0 ? Double(config.temperature) : defaultCfg
        let steps = config.maxTokens > 0 ? config.maxTokens : defaultSteps
        let negative = config.systemPrompt.isEmpty ? defaultNegative : config.systemPrompt
        // seed：取当前时间纳秒取模 1_000_000_000，与 Android System.currentTimeMillis() % 1_000_000_000L 对齐
        let seed = Int64(Date().timeIntervalSince1970 * 1000) % 1_000_000_000

        return [
            nodeKSampler: buildKSampler(seed: seed, steps: steps, cfg: cfg),
            nodeCheckpointLoader: buildCheckpointLoader(checkpoint: checkpoint),
            nodeEmptyLatent: buildEmptyLatent(),
            nodePositivePrompt: buildClipTextEncode(text: prompt),
            nodeNegativePrompt: buildClipTextEncode(text: negative),
            nodeVaeDecode: buildVaeDecode(),
            nodeSaveImage: buildSaveImage()
        ]
    }

    // MARK: - Node Builders

    private static func buildKSampler(seed: Int64, steps: Int, cfg: Double) -> [String: Any] {
        return [
            "class_type": "KSampler",
            "inputs": [
                "seed": seed,
                "steps": steps,
                "cfg": cfg,
                "sampler_name": defaultSampler,
                "scheduler": defaultScheduler,
                "denoise": 1.0,
                "model": nodeRef(nodeCheckpointLoader, 0),
                "positive": nodeRef(nodePositivePrompt, 0),
                "negative": nodeRef(nodeNegativePrompt, 0),
                "latent_image": nodeRef(nodeEmptyLatent, 0)
            ] as [String: Any]
        ]
    }

    private static func buildCheckpointLoader(checkpoint: String) -> [String: Any] {
        return [
            "class_type": "CheckpointLoaderSimple",
            "inputs": [
                "ckpt_name": checkpoint
            ] as [String: Any]
        ]
    }

    private static func buildEmptyLatent() -> [String: Any] {
        return [
            "class_type": "EmptyLatentImage",
            "inputs": [
                "width": defaultWidth,
                "height": defaultHeight,
                "batch_size": defaultBatchSize
            ] as [String: Any]
        ]
    }

    private static func buildClipTextEncode(text: String) -> [String: Any] {
        return [
            "class_type": "CLIPTextEncode",
            "inputs": [
                "text": text,
                "clip": nodeRef(nodeCheckpointLoader, 1)
            ] as [String: Any]
        ]
    }

    private static func buildVaeDecode() -> [String: Any] {
        return [
            "class_type": "VAEDecode",
            "inputs": [
                "samples": nodeRef(nodeKSampler, 0),
                "vae": nodeRef(nodeCheckpointLoader, 2)
            ] as [String: Any]
        ]
    }

    private static func buildSaveImage() -> [String: Any] {
        return [
            "class_type": "SaveImage",
            "inputs": [
                "filename_prefix": "ComfyUI",
                "images": nodeRef(nodeVaeDecode, 0)
            ] as [String: Any]
        ]
    }

    /// 节点引用数组：`["<node_id>", <output_index>]`。
    /// 返回 `[Any]`（String + Int 混合），JSONSerialization 会正确序列化为 JSON 数组。
    private static func nodeRef(_ nodeId: String, _ outputIndex: Int) -> [Any] {
        return [nodeId, outputIndex]
    }
}
