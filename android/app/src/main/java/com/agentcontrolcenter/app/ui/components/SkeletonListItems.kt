package com.agentcontrolcenter.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 会话列表骨架屏 — 3 行占位
 */
@Composable
fun SessionSkeletonItem() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SkeletonBox(
            modifier = Modifier.size(48.dp),
            cornerRadius = 24
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(16.dp)
            )
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(12.dp)
            )
        }
    }
}

/**
 * 消息列表骨架屏
 */
@Composable
fun MessageSkeletonItem() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(16.dp)
        )
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(16.dp)
        )
    }
}

/**
 * Agent 卡片骨架屏
 */
@Composable
fun AgentCardSkeletonItem() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SkeletonBox(
            modifier = Modifier.size(56.dp),
            cornerRadius = 28
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.5f).height(18.dp))
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.7f).height(14.dp))
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.3f).height(12.dp))
        }
    }
}
