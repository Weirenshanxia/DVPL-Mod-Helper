package com.dvpl.modhelper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 打包预览对话框（新版：基于文件夹匹配，匹配已在 buildPackPlans 完成）
 * 展示每个库的匹配结果，有错误时禁止打包。
 */
@Composable
fun PackPreviewDialog(
    plans: List<PackPlan>,
    onDismiss: () -> Unit,
    onConfirm: (List<PackPlan>, rebuildFreshIds: Boolean) -> Unit
) {
    // 全新 ID 重建仅对自带 HIRC 的 bnk 有意义
    val anyHirc = plans.any { it.bank.hircSize > 0 }
    var rebuildFreshIds by remember { mutableStateOf(false) }
    val totalMatched = plans.sumOf { it.replacements.size }
    val totalEmpty = plans.sumOf { it.emptySlots }
    val totalConverted = plans.sumOf { it.autoConverted }
    val allErrors = plans.flatMap { plan ->
        plan.errors.map { plan.bankName + ": " + it }
    }
    val allUnmatched = plans.flatMap { plan ->
        plan.unmatchedFolders.map { plan.bankName + ": " + it }
    }
    val hasErrors = allErrors.isNotEmpty()
    val canPack = !hasErrors && totalMatched > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.s(R.string.pack_preview_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 统计头
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasErrors)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                if (hasErrors) Icons.Default.Error else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (hasErrors) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                L.s(R.string.pack_preview_matched) + ": " + totalMatched,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (totalEmpty > 0) {
                            Text(
                                totalEmpty.toString() + L.s(R.string.x_empty_slots_skipped),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (totalConverted > 0) {
                            Text(
                                totalConverted.toString() + L.s(R.string.x_auto_converted),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (allUnmatched.isNotEmpty()) {
                            Text(
                                allUnmatched.size.toString() + L.s(R.string.pack_preview_unmatched),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 错误列表
                if (allErrors.isNotEmpty()) {
                    Text(
                        L.s(R.string.pack_preview_errors),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 180.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(allErrors) { err ->
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Error, contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // 未匹配文件夹明细（有用户音频但目标不明，静默跳过会导致用户误以为已处理）
                if (allUnmatched.isNotEmpty()) {
                    Text(
                        L.s(R.string.pack_preview_unmatched_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 120.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(allUnmatched) { path ->
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 各库匹配明细
                for (plan in plans) {
                    if (plans.size > 1) {
                        Text(
                            plan.bankName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (plan.replacements.isNotEmpty()) {
                        Text(
                            L.s(R.string.pack_preview_matched) + ": " + plan.replacements.size +
                                L.s(R.string.x_pack_entries),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // 全新 ID 重建开关
                if (anyHirc) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                L.s(R.string.pack_rebuild),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                L.s(R.string.pack_rebuild_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = rebuildFreshIds,
                            onCheckedChange = { rebuildFreshIds = it }
                        )
                    }
                }

                // 提示
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Info, contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        L.s(R.string.pack_preview_slow_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!canPack && !hasErrors) {
                    Text(
                        L.s(R.string.x_none_matched),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(plans, rebuildFreshIds) },
                enabled = canPack
            ) {
                Text(L.s(R.string.b_start_pack))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L.s(R.string.b_cancel))
            }
        }
    )
}

