import Foundation

// MARK: - ComfyImageOutput
// 对应 Android com.agentcontrolcenter.app.transport.comfyui.ComfyImageOutput / ComfyImageOutputExtractor

/// ComfyUI 图片输出信息（filename + subfolder + type）。
///
/// 通过 `/view` 端点下载，对应查询参数与字段同名。
struct ComfyImageOutput: Sendable, Equatable {
    let filename: String
    let subfolder: String
    let type: String

    init(filename: String, subfolder: String = "", type: String = "output") {
        self.filename = filename
        self.subfolder = subfolder
        self.type = type
    }

    /// 构造 `/view` 端点 URL（含 query 参数）。
    ///
    /// filename / subfolder 做 URL 编码，type 直接拼接（"output"/"temp"）。
    /// - Parameter base: ComfyUI 服务 base URL（无尾斜杠）
    /// - Returns: 完整的 `/view` URL 字符串
    func viewUrl(base: String) -> String {
        let encodedFilename = filename.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? filename
        let encodedSubfolder = subfolder.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? subfolder
        return "\(base)/view?filename=\(encodedFilename)&subfolder=\(encodedSubfolder)&type=\(type)"
    }
}

// MARK: - ComfyImageOutputExtractor

/// 从 ComfyUI `/history/{prompt_id}` 响应中提取所有图片输出。
///
/// history 响应格式：
/// ```
/// {
///   "<prompt_id>": {
///     "outputs": {
///       "9": {  // SaveImage 节点 ID
///         "images": [
///           { "filename": "ComfyUI_00001_.png", "subfolder": "", "type": "output" }
///         ]
///       }
///     }
///   }
/// }
/// ```
enum ComfyImageOutputExtractor {

    /// 从 history 响应的 promptEntry 字典中提取图片输出列表。
    ///
    /// - Parameter promptEntry: `history[promptId]` 对应的字典
    /// - Returns: 提取到的图片输出列表；无 outputs / 无 images 时返回空数组
    static func extract(from promptEntry: [String: Any]) -> [ComfyImageOutput] {
        guard let outputs = promptEntry["outputs"] as? [String: Any] else { return [] }
        var images: [ComfyImageOutput] = []
        for (_, nodeOutput) in outputs {
            guard let outputObj = nodeOutput as? [String: Any],
                  let imagesArray = outputObj["images"] as? [Any] else { continue }
            for imgElement in imagesArray {
                guard let img = imgElement as? [String: Any],
                      let filename = img["filename"] as? String else { continue }
                let subfolder = (img["subfolder"] as? String) ?? ""
                let type = (img["type"] as? String) ?? "output"
                images.append(ComfyImageOutput(filename: filename, subfolder: subfolder, type: type))
            }
        }
        return images
    }
}
