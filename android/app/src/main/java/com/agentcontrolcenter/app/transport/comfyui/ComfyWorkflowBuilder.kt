package com.agentcontrolcenter.app.transport.comfyui

import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 构造 ComfyUI 工作流 JSON。
 *
 * ComfyUI 工作流格式：节点 ID 为键，值为 `{class_type, inputs}` 对象。
 * 节点间引用为 `["<node_id>", <output_index>]` 数组。
 */
internal object ComfyWorkflowBuilder {

    private const val DEFAULT_CHECKPOINT = "v1-5-pruned-emaonly.safetensors"
    private const val DEFAULT_CFG = 7.0f
    private const val DEFAULT_STEPS = 20
    private const val DEFAULT_NEGATIVE = "bad quality, blurry"
    private const val DEFAULT_WIDTH = 512
    private const val DEFAULT_HEIGHT = 512
    private const val DEFAULT_BATCH_SIZE = 1
    private const val DEFAULT_SAMPLER = "euler"
    private const val DEFAULT_SCHEDULER = "normal"

    // 节点 ID 常量（与 ComfyUI 默认导出保持一致）
    private const val NODE_KSAMPLER = "3"
    private const val NODE_CHECKPOINT_LOADER = "4"
    private const val NODE_EMPTY_LATENT = "5"
    private const val NODE_POSITIVE_PROMPT = "6"
    private const val NODE_NEGATIVE_PROMPT = "7"
    private const val NODE_VAE_DECODE = "8"
    private const val NODE_SAVE_IMAGE = "9"

    /**
     * 构造默认文生图工作流。
     *
     * AgentConfig 字段语义复用：
     * - [AgentConfig.model] → checkpoint 文件名（如 `v1-5-pruned-emaonly.safetensors`）
     * - [AgentConfig.temperature] → cfg scale（默认 7.0）
     * - [AgentConfig.maxTokens] → steps 采样步数（默认 20）
     * - [AgentConfig.systemPrompt] → 负向提示词（negative prompt）
     *
     * 节点结构：
     * - "3": KSampler（采样器）
     * - "4": CheckpointLoaderSimple（模型加载）
     * - "5": EmptyLatentImage（空 latent）
     * - "6": CLIPTextEncode positive（正向提示词）
     * - "7": CLIPTextEncode negative（负向提示词）
     * - "8": VAEDecode（VAE 解码）
     * - "9": SaveImage（保存图片）
     */
    fun buildTextToImageWorkflow(prompt: String, config: AgentConfig): JsonObject {
        val checkpoint = config.model.ifBlank { DEFAULT_CHECKPOINT }
        val cfg = if (config.temperature > 0) config.temperature else DEFAULT_CFG
        val steps = if (config.maxTokens > 0) config.maxTokens else DEFAULT_STEPS
        val negative = config.systemPrompt.ifBlank { DEFAULT_NEGATIVE }
        val seed = System.currentTimeMillis() % 1_000_000_000L

        return JsonObject().apply {
            add(NODE_KSAMPLER, buildKSampler(seed, steps, cfg))
            add(NODE_CHECKPOINT_LOADER, buildCheckpointLoader(checkpoint))
            add(NODE_EMPTY_LATENT, buildEmptyLatent())
            add(NODE_POSITIVE_PROMPT, buildClipTextEncode(prompt))
            add(NODE_NEGATIVE_PROMPT, buildClipTextEncode(negative))
            add(NODE_VAE_DECODE, buildVaeDecode())
            add(NODE_SAVE_IMAGE, buildSaveImage())
        }
    }

    private fun buildKSampler(seed: Long, steps: Int, cfg: Float) = JsonObject().apply {
        addProperty("class_type", "KSampler")
        add("inputs", JsonObject().apply {
            addProperty("seed", seed)
            addProperty("steps", steps)
            addProperty("cfg", cfg)
            addProperty("sampler_name", DEFAULT_SAMPLER)
            addProperty("scheduler", DEFAULT_SCHEDULER)
            addProperty("denoise", 1.0f)
            add("model", nodeRef(NODE_CHECKPOINT_LOADER, 0))
            add("positive", nodeRef(NODE_POSITIVE_PROMPT, 0))
            add("negative", nodeRef(NODE_NEGATIVE_PROMPT, 0))
            add("latent_image", nodeRef(NODE_EMPTY_LATENT, 0))
        })
    }

    private fun buildCheckpointLoader(checkpoint: String) = JsonObject().apply {
        addProperty("class_type", "CheckpointLoaderSimple")
        add("inputs", JsonObject().apply {
            addProperty("ckpt_name", checkpoint)
        })
    }

    private fun buildEmptyLatent() = JsonObject().apply {
        addProperty("class_type", "EmptyLatentImage")
        add("inputs", JsonObject().apply {
            addProperty("width", DEFAULT_WIDTH)
            addProperty("height", DEFAULT_HEIGHT)
            addProperty("batch_size", DEFAULT_BATCH_SIZE)
        })
    }

    private fun buildClipTextEncode(text: String) = JsonObject().apply {
        addProperty("class_type", "CLIPTextEncode")
        add("inputs", JsonObject().apply {
            addProperty("text", text)
            add("clip", nodeRef(NODE_CHECKPOINT_LOADER, 1))
        })
    }

    private fun buildVaeDecode() = JsonObject().apply {
        addProperty("class_type", "VAEDecode")
        add("inputs", JsonObject().apply {
            add("samples", nodeRef(NODE_KSAMPLER, 0))
            add("vae", nodeRef(NODE_CHECKPOINT_LOADER, 2))
        })
    }

    private fun buildSaveImage() = JsonObject().apply {
        addProperty("class_type", "SaveImage")
        add("inputs", JsonObject().apply {
            addProperty("filename_prefix", "ComfyUI")
            add("images", nodeRef(NODE_VAE_DECODE, 0))
        })
    }

    /** 节点引用数组：`["<node_id>", <output_index>]`。 */
    private fun nodeRef(nodeId: String, outputIndex: Int) =
        JsonArray().apply { add(nodeId); add(outputIndex) }
}
