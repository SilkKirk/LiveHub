package com.livehub.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The small label above a group of cards — 外观, 弹幕, 同步内容, 我的设备.
 *
 * It used to be a `titleSmall`, which made every section heading *smaller* than the card title it
 * introduced (`主题` is a `titleMedium`): a page of groups read as a list of equally-important
 * lines, and the thing that named a group looked like a footnote to it.
 *
 * This is the other way round on purpose. An eyebrow is deliberately quieter than the title under
 * it — smaller, muted, and spaced out — so the eye lands on the card's own heading first and uses
 * this only to know where one group ends and the next begins. `letterSpacing` is what makes a 12sp
 * label read as a label rather than as small text.
 *
 * Shared by 设置 and 同步 because they are the same kind of page: a stack of groups. Two private
 * copies of this had already drifted (one `titleSmall`, one `titleMedium` for the same job).
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.8.sp,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp),
  )
}
