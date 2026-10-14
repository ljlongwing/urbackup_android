package org.urbackup.client.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A visible row of the tree: a folder relative to the storage root, and how deep it is. */
private data class TreeRow(val path: String, val depth: Int)

/**
 * Picks folders from a tree of the phone's storage. Checking a folder includes everything below it,
 * so its subfolders show as checked and cannot be picked on their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPicker(initial: List<String>, onDone: (List<String>) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    var selected by remember { mutableStateOf(initial.toSet()) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // subfolders per folder ("" = storage root), loaded when a folder is expanded
    val children = remember { mutableStateMapOf<String, List<String>>() }

    // loads the subfolders of [p], and theirs too, so folders without subfolders get no arrow
    suspend fun load(p: String) {
        val list = children[p] ?: listSubfolders(p).also { children[p] = it }
        for (c in list) {
            if (c !in children) children[c] = listSubfolders(c)
        }
    }

    LaunchedEffect(Unit) {
        load("")
        // open the way to the folders that are already selected
        for (f in initial) {
            val parts = f.split('/')
            for (i in 1 until parts.size) {
                val p = parts.take(i).joinToString("/")
                expanded[p] = true
                load(p)
            }
        }
    }

    val rows = mutableListOf<TreeRow>()
    fun addRows(parent: String, depth: Int) {
        for (c in children[parent] ?: return) {
            rows += TreeRow(c, depth)
            if (expanded[c] == true) addRows(c, depth + 1)
        }
    }
    addRows("", 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose folders") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(onClick = { onDone(selected.sorted()) }) { Text("Done") }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Checking a folder includes everything in it.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(rows, key = { it.path }) { row ->
                val ancestorSelected = ancestors(row.path).any { it in selected }
                val isOpen = expanded[row.path] == true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            // tapping the name opens the folder
                            expanded[row.path] = !isOpen
                        }
                        .padding(start = (8 + row.depth * 20).dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val known = children[row.path]
                    if (known == null || known.isNotEmpty()) {
                        Icon(
                            if (isOpen) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (isOpen) "Close" else "Open",
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Spacer(Modifier.width(24.dp))
                    }
                    Checkbox(
                        checked = ancestorSelected || row.path in selected,
                        enabled = !ancestorSelected,
                        onCheckedChange = { checked ->
                            selected = if (checked) {
                                // a folder includes its subfolders, so they need no entry of their own
                                selected.filterNot { it.startsWith(row.path + "/") }.toSet() + row.path
                            } else {
                                selected - row.path
                            }
                        }
                    )
                    Text(
                        row.path.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (isOpen) {
                    LaunchedEffect(row.path) { load(row.path) }
                }
            }
        }
    }
}

private fun ancestors(path: String): List<String> {
    val parts = path.split('/')
    return (1 until parts.size).map { parts.take(it).joinToString("/") }
}

/** Subfolders of [path] (relative to the storage root), without hidden ones, sorted by name. */
private suspend fun listSubfolders(path: String): List<String> = withContext(Dispatchers.IO) {
    val dir = File(AppConfig.absolutePath(path).trimEnd('/'))
    val dirs = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray()
    dirs.map { if (path.isEmpty()) it.name else "$path/${it.name}" }
        .sortedBy { it.substringAfterLast('/').lowercase() }
}
