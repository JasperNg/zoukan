package zoukan.desktop.ui.category

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.HideCategory
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton

/** Android's CategoryScreenModel: the user's categories, without the system Default one. */
class CategoryScreenModel(
    private val getCategories: GetCategories = Injekt.get(),
    private val createCategoryWithName: CreateCategoryWithName = Injekt.get(),
    private val deleteCategory: DeleteCategory = Injekt.get(),
    private val reorderCategory: ReorderCategory = Injekt.get(),
    private val renameCategory: RenameCategory = Injekt.get(),
    private val hideCategory: HideCategory = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : StateScreenModel<CategoryScreenModel.State>(State()) {

    data class State(
        val categories: List<Category>? = null,
        val dialog: CategoryDialog? = null,
    )

    /** The category new entries go to: -1 asks, 0 is Default, as Settings > Library on Android. */
    val defaultCategory = libraryPreferences.defaultCategory()

    init {
        screenModelScope.launch {
            getCategories.subscribe().collectLatest { categories ->
                mutableState.update { it.copy(categories = categories.filterNot(Category::isSystemCategory)) }
            }
        }
    }

    fun createCategory(name: String) = launchReporting {
        createCategoryWithName.await(name) is CreateCategoryWithName.Result.InternalError
    }

    fun hideCategory(category: Category) = launchReporting {
        hideCategory.await(category) is HideCategory.Result.InternalError
    }

    fun deleteCategory(categoryId: Long) = launchReporting {
        deleteCategory.await(categoryId = categoryId) is DeleteCategory.Result.InternalError
    }

    fun changeOrder(category: Category, newIndex: Int) = launchReporting {
        reorderCategory.await(category, newIndex) is ReorderCategory.Result.InternalError
    }

    fun renameCategory(category: Category, name: String) = launchReporting {
        renameCategory.await(category, name) is RenameCategory.Result.InternalError
    }

    fun showDialog(dialog: CategoryDialog) = mutableState.update { it.copy(dialog = dialog) }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    private fun launchReporting(block: suspend () -> Boolean) {
        screenModelScope.launch {
            if (block()) Toasts.show("An internal error occurred")
        }
    }
}

sealed interface CategoryDialog {
    data object Create : CategoryDialog
    data class Rename(val category: Category) : CategoryDialog
    data class Delete(val category: Category) : CategoryDialog
}

/** Android's Edit categories screen: create, rename, hide, delete and drag to reorder. */
object CategoryScreen : Screen {
    private fun readResolve(): Any = CategoryScreen

    override val key: ScreenKey = "categories"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { CategoryScreenModel() }
        val state by model.state.collectAsState()
        val categories = state.categories

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                ScreenHeader("Edit categories")
                when {
                    categories == null -> LoadingScreen()
                    else -> {
                        DefaultCategoryRow(model, categories)
                        if (categories.isEmpty()) {
                            EmptyScreen("You have no categories. Click the plus button to create one for organizing your library.")
                        } else {
                            CategoryList(model, categories)
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                text = { Text("Add") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { model.showDialog(CategoryDialog.Create) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).testTag("category-add"),
            )
        }

        val names = categories.orEmpty().map { it.name }
        when (val dialog = state.dialog) {
            null -> {}
            CategoryDialog.Create -> CategoryNameDialog(
                title = "Add category",
                confirmLabel = "Add",
                initial = "",
                existing = names,
                onDismiss = model::dismissDialog,
                onConfirm = model::createCategory,
            )
            is CategoryDialog.Rename -> CategoryNameDialog(
                title = "Rename category",
                confirmLabel = "OK",
                initial = dialog.category.name,
                existing = names,
                onDismiss = model::dismissDialog,
                onConfirm = { model.renameCategory(dialog.category, it) },
            )
            is CategoryDialog.Delete -> AlertDialog(
                onDismissRequest = model::dismissDialog,
                title = { Text("Delete category") },
                text = { Text("Do you wish to delete the category \"${dialog.category.name}\"?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            model.deleteCategory(dialog.category.id)
                            model.dismissDialog()
                        },
                        modifier = Modifier.testTag("category-confirm"),
                    ) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = model::dismissDialog) { Text("Cancel") } },
            )
        }
    }
}

/** Settings > Library > Default category on Android: where an entry goes when it is added. */
@Composable
private fun DefaultCategoryRow(model: CategoryScreenModel, categories: List<Category>) {
    val selected by model.defaultCategory.changes().collectAsState(model.defaultCategory.get())
    var menu by remember { mutableStateOf(false) }
    val label = when (selected) {
        -1 -> "Always ask"
        0 -> "Default"
        else -> categories.firstOrNull { it.id == selected.toLong() }?.name ?: "Always ask"
    }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Default category", style = MaterialTheme.typography.titleMedium)
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            val options = listOf(-1 to "Always ask", 0 to "Default") + categories.map { it.id.toInt() to it.name }
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        menu = false
                        model.defaultCategory.set(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun CategoryList(model: CategoryScreenModel, categories: List<Category>) {
    val lazyListState = rememberLazyListState()
    // As on Android: the list moves while dragging and the database follows each move
    val items = remember { categories.toMutableStateList() }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val item = items.removeAt(from.index)
        items.add(to.index, item)
        model.changeOrder(item, to.index)
    }
    LaunchedEffect(categories) {
        if (!reorderableState.isAnyItemDragging) {
            items.clear()
            items.addAll(categories)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = lazyListState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { "category-${it.id}" }) { category ->
            ReorderableItem(reorderableState, "category-${category.id}") {
                CategoryListItem(
                    category = category,
                    onRename = { model.showDialog(CategoryDialog.Rename(category)) },
                    onHide = { model.hideCategory(category) },
                    onDelete = { model.showDialog(CategoryDialog.Delete(category)) },
                )
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.CategoryListItem(
    category: Category,
    onRename: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(Modifier.testTag("category-item")) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onRename).padding(vertical = 4.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.DragHandle,
                contentDescription = "Reorder",
                modifier = Modifier.padding(12.dp).draggableHandle(),
            )
            Text(
                category.name,
                color = LocalContentColor.current.let { if (category.hidden) it.copy(alpha = 0.6f) else it },
                textDecoration = TextDecoration.LineThrough.takeIf { category.hidden },
                modifier = Modifier.weight(1f),
            )
            TooltipIconButton(Icons.Outlined.Edit, "Rename category", onClick = onRename)
            TooltipIconButton(
                if (category.hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                if (category.hidden) "Show" else "Hide",
                onClick = onHide,
            )
            TooltipIconButton(Icons.Outlined.Delete, "Delete", onClick = onDelete)
        }
    }
}

/** Android's CategoryCreateDialog and CategoryRenameDialog: a name, unique among the categories. */
@Composable
private fun CategoryNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    existing: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val changed = name != initial
    val exists = changed && name in existing
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                supportingText = { Text(if (name.isNotEmpty() && exists) "A category with this name already exists!" else "*required") },
                isError = name.isNotEmpty() && exists,
                singleLine = true,
                modifier = Modifier.focusRequester(focusRequester).testTag("category-name"),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotEmpty() && changed && !exists,
                onClick = {
                    onConfirm(name)
                    onDismiss()
                },
                modifier = Modifier.testTag("category-confirm"),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}
