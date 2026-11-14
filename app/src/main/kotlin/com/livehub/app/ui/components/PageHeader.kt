package com.livehub.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The block every page starts with: one large title, one quiet line under it.
 *
 * Before this the five tabs ended their chrome differently — 首页 began with platform tabs, 搜索
 * with a text field, 关注 with a sentence that read "关注 4/5 正在直播" as one run of text, and
 * 同步/设置 simply started with a section label. Nothing was wrong on its own, but switching tabs
 * gave no constant: the eye had to find the page's subject again on every screen.
 *
 * A title that says where you are, and a line that says what the page contains, is one thing to
 * look at and it is the same thing everywhere. The title uses `headlineSmall` (24sp) with a slight
 * negative tracking — at 320dp two Chinese characters at this size are the heaviest text on the
 * screen without being shouty — and the subtitle stays at `labelMedium` (12sp) so it explains
 * rather than competes.
 *
 * The block scrolls with the content it introduces: these pages are lists, and a header that stays
 * pinned would take a fifth of a small phone forever. 关注 additionally folds it away on scroll
 * (`CollapsibleHeader`), which is why nothing here is sticky by design.
 */
@Composable
fun PageHeader(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  /**
   * Left/right inset, in dp.
   *
   * 16dp matches the cards on 设置 and 同步, which is where a masthead sits directly above a card.
   * 关注 passes 12dp because its content — the avatar strip and the chip rows — is inset 12dp, and a
   * title that overhangs the list it introduces by 4dp reads as a mistake rather than as a decision.
   */
  inset: Dp = 16.dp,
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = inset, end = inset, top = 14.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 2.dp),
        )
      }
    }
    trailing?.invoke()
  }
}
