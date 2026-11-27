package dev.kstep.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import dev.kstep.desktop.pick.MenuAction
import dev.kstep.desktop.pick.MenuKey
import dev.kstep.desktop.pick.MenuModel
import dev.kstep.desktop.pick.MenuStep
import dev.kstep.desktop.pick.PartContextMenu

/** Where a context menu was asked for: the canvas or the assembly tree. */
internal enum class MenuOrigin { Viewer, Tree }

/**
 * A request for the context menu (ADR-0092). [x] and [y] are pixels in the origin's own area; [centered] (Menu key in
 * the viewer) ignores them. [overPart] says whether the pointer was on a part or tree row.
 */
internal class MenuRequest(
    val origin: MenuOrigin,
    val x: Float = 0f,
    val y: Float = 0f,
    val overPart: Boolean = true,
    val centered: Boolean = false,
)

private val MENU_WIDTH = 240.dp
private val ROW_HEIGHT = 28.dp
private const val DISABLED_ALPHA = 0.45f
private const val FOCUS_ALPHA = 0.14f

/** The key a menu understands, `null` for every other key. Pure, so it can be tested without a window. */
internal fun menuKeyOf(event: KeyEvent): MenuKey? {
    if (event.type != KeyEventType.KeyDown) return null
    return when (event.key) {
        Key.DirectionUp -> MenuKey.Up
        Key.DirectionDown -> MenuKey.Down
        Key.MoveHome -> MenuKey.Home
        Key.MoveEnd -> MenuKey.End
        Key.Enter, Key.NumPadEnter -> MenuKey.Enter
        Key.Escape -> MenuKey.Escape
        else -> null
    }
}

/**
 * Places [content] (the popup) at the request's position inside a Box: a zero-size anchor at the pointer, or at the
 * centre of the area for a key-opened menu.
 */
@Composable
internal fun BoxScope.MenuAnchor(
    request: MenuRequest,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val modifier =
        if (request.centered) {
            Modifier.align(Alignment.Center)
        } else {
            Modifier.align(Alignment.TopStart).offset(
                with(density) { request.x.toDp() },
                with(density) { request.y.toDp() },
            )
        }
    Box(modifier) { content() }
}

/**
 * The part context menu (ADR-0092): a [DropdownMenu] whose entries are drawn here, because a disabled entry must stay
 * reachable with Up and Down (so the reason can be read), which the stock item does not allow. The reason of the
 * focused or hovered disabled entry shows in the footnote and as a tooltip. Names are only ever drawn as text. When the
 * menu closes, [restoreFocus] gives the focus back to where it came from.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun PartContextMenuPopup(
    initial: MenuModel,
    offset: DpOffset = DpOffset.Zero,
    onAction: (MenuAction) -> Unit,
    onDismiss: () -> Unit,
    restoreFocus: () -> Unit,
) {
    var model by remember(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val textColor = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary

    fun close() {
        onDismiss()
        restoreFocus()
    }

    fun activate(index: Int) {
        val entry = model.entries.getOrNull(index) ?: return
        if (!entry.enabled) {
            model = model.copy(focused = index)
            return
        }
        onAction(entry.action)
        close()
    }
    DropdownMenu(
        expanded = true,
        onDismissRequest = { close() },
        offset = offset,
        properties = PopupProperties(focusable = true),
    ) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(
            Modifier
                .width(MENU_WIDTH)
                .onPreviewKeyEvent { event ->
                    val key = menuKeyOf(event) ?: return@onPreviewKeyEvent false
                    when (val step = PartContextMenu.navigate(model, key)) {
                        is MenuStep.Moved -> model = step.model
                        is MenuStep.Activate -> {
                            onAction(step.action)
                            close()
                        }
                        MenuStep.Close -> close()
                        MenuStep.NoOp -> Unit
                    }
                    true
                }.focusRequester(focus)
                .focusable()
                .semantics { contentDescription = "Part menu" },
        ) {
            model.header?.let { header ->
                Text(
                    header,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = textColor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            model.entries.forEachIndexed { index, entry ->
                val focused = index == model.focused
                val row =
                    Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .background(if (focused) accent.copy(alpha = FOCUS_ALPHA) else Color.Transparent)
                        .onPointerEvent(PointerEventType.Enter) { model = model.copy(focused = index) }
                        .clickable { activate(index) }
                        .semantics {
                            role = Role.Button
                            contentDescription = entry.label
                            if (!entry.enabled) {
                                disabled()
                                stateDescription = entry.reason ?: "unavailable"
                            }
                        }
                val label: @Composable () -> Unit = {
                    Box(row, contentAlignment = Alignment.CenterStart) {
                        Text(
                            entry.label,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (entry.enabled) textColor else textColor.copy(alpha = DISABLED_ALPHA),
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                val reason = entry.reason
                if (!entry.enabled && reason != null) {
                    TooltipArea(
                        tooltip = {
                            Box(Modifier.background(Color.Black.copy(alpha = 0.8f)).padding(6.dp)) {
                                Text(reason, color = Color.White, fontSize = 12.sp)
                            }
                        },
                    ) { label() }
                } else {
                    label()
                }
            }
            model.focusedEntry?.takeIf { !it.enabled }?.reason?.let { reason ->
                Text(
                    reason,
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
