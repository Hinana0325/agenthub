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
