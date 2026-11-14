package com.livehub.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The one chip in the app: a filled pill, no border, brand container when selected.
 *
 * There were seventeen raw `FilterChip` call sites and they had drifted into two languages for the
 * same state: the home group row showed selection as a pink fill, the partition row as a pink fill
 * *plus a check icon*, and both drew an outline while unselected. Ten or thirty outlined pills on a
 * page is most of what made the home screen feel busy — and two rows of the same tree looking
 * different made two levels of one control read as two kinds of control.
 *
 * Filled and borderless also makes the selected state legible on its own: the pill changes both
 * value and hue, so it needs no icon to say it is on. That is why the check mark is gone rather than
 * being moved into this component.
 */
@Composable
fun LiveHubFilterChip(
  selected: Boolean,
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = label,
    modifier = modifier,
    enabled = enabled,
    shape = RoundedCornerShape(50),
    // No outline: an unselected chip is a filled pill one shade off the page, which is exactly the
    // amount of structure a filter needs. The border was the loudest repeating element on screen.
    border = null,
    colors = FilterChipDefaults.filterChipColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
      labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
      selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
      selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ),
  )
}
