package com.livehub.app.ui.components

import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * `ListItem` colours for a row that sits inside a `Card`, or straight on the page.
 *
 * `ListItem` paints itself `surface`, which is **not** the colour of the `Card` it is usually
 * dropped into: a filled `Card` paints `surfaceContainerHighest`. Under the brand palette those two
 * are the same white in both themes — the light scheme sets both tokens to `SurfaceLight`, the dark
 * one to `SurfaceDark` — so the component's own fill looks correct here by accident. Under Material
 * You, which this app now offers, `surface` and `surfaceContainerHighest` are different tonal steps
 * and every row would paint a second rectangle inside its card, each one offset by 16dp of the
 * card's own inset.
 *
 * So the row gives up its fill and keeps everything else: the headline/supporting/icon colours are
 * still `ListItemDefaults`' (which resolve through `MaterialTheme.colorScheme`), and elevation and
 * shape are `0dp` and square by token. Two screens share this — 设置 and 同步 are the same kind of
 * page — which is why it is one function rather than a private copy in each.
 */
@Composable
fun listRowColors(): ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
