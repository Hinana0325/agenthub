import SwiftUI

/// 首次启动引导页面。
///
/// 通过 TabView 展示 3 页引导：
/// 1. 欢迎页 — Agent Control Center 简介
/// 2. 核心功能 — 端侧 AI Agent 控制
/// 3. 安全与隐私 — 数据加密、本地优先
///
/// 最后一页显示「开始使用」按钮，点击后标记 onboarding 完成并进入主界面。
struct OnboardingView: View {

    /// 点击「开始使用」或「跳过」时的回调
    let onComplete: () -> Void

    /// 当前页面索引
    @State private var currentPage = 0

    private let pages: [OnboardingPageData] = [
        OnboardingPageData(
            icon: "cpu",
            title: "欢迎来到 Agent Control Center",
            description: "统一管理本地与远程 AI Agent，支持 Ollama、LM Studio、OpenAI 等多种后端，在手机上实现端侧 AI 推理与控制。"
        ),
        OnboardingPageData(
            icon: "iphone.and.ipad",
            title: "多设备协同",
            description: "跨平台支持 Android 与 iOS，连接本地推理服务，管理多个 Agent 会话，实时查看推理性能与硬件状态。"
        ),
        OnboardingPageData(
            icon: "lock.shield",
            title: "安全与隐私",
            description: "API Key 通过 iOS Keychain 硬件级加密存储，端到端加密通信，数据本地优先，完全掌控你的 AI。"
        )
    ]

    var body: some View {
        VStack(spacing: 0) {
            // 顶部跳过按钮
            HStack {
                Spacer()
                if currentPage < pages.count - 1 {
                    Button("跳过") { onComplete() }
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .padding(.trailing)
                }
            }
            .frame(height: 44)

            // TabView 引导内容
            TabView(selection: $currentPage) {
                ForEach(Array(pages.enumerated()), id: \.offset) { index, page in
                    OnboardingPageContent(page: page)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))

            // 底部按钮
            VStack(spacing: AppTheme.Spacing.md) {
                Button {
                    if currentPage < pages.count - 1 {
                        withAnimation { currentPage += 1 }
                    } else {
                        onComplete()
                    }
                } label: {
                    HStack {
                        Image(systemName: currentPage == pages.count - 1 ? "checkmark" : "arrow.right")
                        Text(currentPage == pages.count - 1 ? "开始使用" : "下一步")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, AppTheme.Spacing.md)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
            .padding(.horizontal, AppTheme.Spacing.lg)
            .padding(.bottom, AppTheme.Spacing.xl)
        }
    }
}

/// 单页引导内容
private struct OnboardingPageContent: View {
    let page: OnboardingPageData

    var body: some View {
        VStack(spacing: AppTheme.Spacing.lg) {
            Spacer()

            // 图标
            Image(systemName: page.icon)
                .font(.system(size: 72))
                .foregroundStyle(AppTheme.primaryColor)
                .frame(width: 120, height: 120)
                .background(AppTheme.primaryColor.opacity(0.1), in: Circle())

            // 标题
            Text(page.title)
                .font(.title2)
                .fontWeight(.bold)
                .multilineTextAlignment(.center)

            // 描述
            Text(page.description)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, AppTheme.Spacing.xl)

            Spacer()
        }
        .padding(.horizontal, AppTheme.Spacing.lg)
    }
}

/// 引导页数据模型
private struct OnboardingPageData {
    let icon: String
    let title: String
    let description: String
}
