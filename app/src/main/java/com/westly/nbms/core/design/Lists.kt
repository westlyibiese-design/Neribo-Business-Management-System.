package com.westly.nbms.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key as composeKey
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp

/** Search box with a magnifier icon and a clear button. Use inside list pages. */
@Composable
fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "Search…",
    modifier: Modifier = Modifier
) {
    NbmsTextField(
        value = value,
        onValueChange = onValueChange,
        label = "",
        modifier = modifier.fillMaxWidth(),
        placeholder = placeholder,
        leadingIcon = Icons.Outlined.Search,
        trailing = if (value.isNotEmpty()) {
            {
                NbmsButton(
                    text = "Clear search",
                    onClick = { onValueChange("") },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Icon,
                    leadingIcon = Icons.Outlined.Close
                )
            }
        } else null
    )
}

/**
 * Page controls: previous, "Page 2 of 5", next. [page] is 1-based (first page = 1).
 * Nothing is drawn when there is only one page.
 */
@Composable
fun Pagination(
    page: Int,
    pageCount: Int,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pageCount <= 1) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NbmsButton(
            text = "Previous page",
            onClick = { onPage(page - 1) },
            variant = ButtonVariant.Outline,
            size = ButtonSize.Icon,
            enabled = page > 1,
            leadingIcon = Icons.Outlined.ChevronRight,
            modifier = Modifier.rotate(180f)
        )
        Text(
            "Page $page of $pageCount",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NbmsButton(
            text = "Next page",
            onClick = { onPage(page + 1) },
            variant = ButtonVariant.Outline,
            size = ButtonSize.Icon,
            enabled = page < pageCount,
            leadingIcon = Icons.Outlined.ChevronRight
        )
    }
}

/**
 * A simple paged list for phones. Shows [pageSize] items at a time with [Pagination] underneath.
 * It is a plain Column (not lazy) so it can sit inside a page that already scrolls.
 * Shows nothing for an empty list; show an [EmptyState] yourself when [items] is empty.
 */
@Composable
fun <T> PagedList(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    pageSize: Int = 10,
    itemContent: @Composable (T) -> Unit
) {
    var page by remember { mutableIntStateOf(1) }
    val pageCount = if (items.isEmpty()) 1 else (items.size + pageSize - 1) / pageSize
    val safePage = page.coerceIn(1, pageCount)
    val start = (safePage - 1) * pageSize
    val visible = items.subList(start, minOf(items.size, start + pageSize))

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        visible.forEach { item ->
            composeKey(key(item)) { itemContent(item) }
        }
        Pagination(
            page = safePage,
            pageCount = pageCount,
            onPage = { page = it.coerceIn(1, pageCount) },
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
