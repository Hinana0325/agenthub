import SwiftUI

/// 骨架屏闪烁动画组件。
/// 用于数据加载时展示灰色占位块，配合 shimmer 动画。
struct SkeletonBox: View {
    var cornerRadius: CGFloat = 8

    @State private var opacity: Double = 0.3

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius)
            .fill(Color.primary.opacity(opacity))
            .onAppear {
                withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                    opacity = 0.7
                }
            }
    }
}

/// 会话列表骨架屏
struct SessionSkeletonRow: View {
    var body: some View {
        HStack(spacing: 12) {
            SkeletonBox(cornerRadius: 24)
                .frame(width: 48, height: 48)

            VStack(alignment: .leading, spacing: 8) {
                SkeletonBox()
                    .frame(width: 180, height: 16)
                SkeletonBox()
                    .frame(width: 120, height: 12)
            }
            Spacer()
        }
        .padding(16)
    }
}

/// 消息列表骨架屏
struct MessageSkeletonRow: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SkeletonBox()
                .frame(maxWidth: .infinity)
                .frame(height: 16)
            SkeletonBox()
                .frame(width: 200, height: 16)
        }
        .padding(16)
    }
}

/// Agent 卡片骨架屏
struct AgentCardSkeletonRow: View {
    var body: some View {
        HStack(spacing: 12) {
            SkeletonBox(cornerRadius: 28)
                .frame(width: 56, height: 56)

            VStack(alignment: .leading, spacing: 8) {
                SkeletonBox()
                    .frame(width: 120, height: 18)
                SkeletonBox()
                    .frame(width: 180, height: 14)
                SkeletonBox()
                    .frame(width: 80, height: 12)
            }
            Spacer()
        }
        .padding(16)
    }
}

/// 通用骨架列表：在 List / ScrollView 中按指定次数重复渲染骨架行。
///
/// 用法（List 内首屏骨架占位）：
/// ```swift
/// List {
///     if isLoading {
///         SkeletonList(repeat: 5) { SessionSkeletonRow() }
///     } else {
///         ForEach(data) { ... }
///     }
/// }
/// ```
struct SkeletonList<Content: View>: View {
    /// 骨架行重复次数
    let repeatCount: Int
    /// 单行骨架内容
    let content: () -> Content

    init(repeat count: Int = 5, @ViewBuilder content: @escaping () -> Content) {
        self.repeatCount = max(1, count)
        self.content = content
    }

    var body: some View {
        ForEach(0..<repeatCount, id: \.self) { _ in
            content()
        }
    }
}

/// 市场卡片骨架屏（对齐 MarketplaceAgentCard 的布局：图标 + 名称 + 描述 + 安装按钮占位）
struct MarketplaceCardSkeletonRow: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                SkeletonBox(cornerRadius: 8)
                    .frame(width: 40, height: 40)
                Spacer()
                SkeletonBox()
                    .frame(width: 24, height: 16)
            }
            SkeletonBox()
                .frame(width: 100, height: 18)
            SkeletonBox()
                .frame(width: 70, height: 12)
            HStack(spacing: 8) {
                SkeletonBox()
                    .frame(width: 40, height: 12)
                SkeletonBox()
                    .frame(width: 50, height: 12)
            }
            SkeletonBox(cornerRadius: 6)
                .frame(maxWidth: .infinity)
                .frame(height: 28)
        }
        .padding(16)
        .background(Color.gray.opacity(0.05), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// 通用列表行骨架屏（用于无现成骨架行的页面：TasksView / ActivityView / DeviceSyncView 等）
struct ListRowSkeleton: View {
    var body: some View {
        HStack(spacing: 12) {
            SkeletonBox(cornerRadius: 16)
                .frame(width: 32, height: 32)

            VStack(alignment: .leading, spacing: 6) {
                SkeletonBox()
                    .frame(width: 160, height: 14)
                SkeletonBox()
                    .frame(width: 100, height: 10)
            }
            Spacer()
        }
        .padding(.vertical, 8)
    }
}
