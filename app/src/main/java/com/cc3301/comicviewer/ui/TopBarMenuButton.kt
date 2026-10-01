package com.cc3301.comicviewer.ui

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import com.cc3301.comicviewer.core.input.LongPressGesture
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 顶栏下拉菜单的骨架（收口）：按钮文字 = 当前值，菜单项逐项渲染、当前项打勾、点选后关闭菜单。
 *
 * 「视图」档位（[ViewMenuButton]）与「排序」（[SortMenuButton]）原先各写一遍同样的十来行骨架，
 * 现在只留这一份；两者的差别都用参数表达。
 *
 * @param buttonLabel 按钮上显示的当前值文案（与 [labelOf] 同一份来源时两边恒等）
 * @param items 菜单项，顺序即渲染顺序
 * @param labelOf 菜单项 → 文案
 * @param isCurrent 菜单项是否当前生效（打勾判据）
 * @param checkDescription 打勾图标的无障碍描述
 * @param onSelect 点选回调（骨架负责随后关闭菜单）
 * @param onLongClick 长按回调；`null`（默认）= 不识别长按，点按照旧（「视图」档位就是这么用的）
 */
@Composable
internal fun <T> TopBarMenuButton(
    buttonLabel: String,
    items: List<T>,
    labelOf: (T) -> String,
    isCurrent: (T) -> Boolean,
    checkDescription: String,
    onSelect: (T) -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    // 手势协程比组合活得久：长按回调每次重组都取最新的一份（键保持 `Unit`，不因 lambda 重建而重启手势）
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    TextButton(
        onClick = { open = true },
        modifier = if (onLongClick == null) {
            Modifier
        } else {
            Modifier.pointerInput(Unit) { detectLongPress { currentOnLongClick?.invoke() } }
        },
    ) { Text(buttonLabel) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(labelOf(item)) },
                trailingIcon = {
                    if (isCurrent(item)) Icon(Icons.Filled.Check, contentDescription = checkDescription)
                },
                onClick = {
                    onSelect(item)
                    open = false
                },
            )
        }
    }
}

/**
 * 长按识别器：挂在按钮**自己**的 modifier 上、走 [PointerEventPass.Initial] ⇒ 先于按钮内部的
 * `clickable`（Main 传递）看到事件，因此可以决定「这一串按下归谁」。判定在 [LongPressGesture] 里（有单测），
 * 本函数只做两件事：把指针事件翻译成它的输入、长按成立后把这一串按下的剩余事件 `consume()` 掉。
 *
 * **为什么不用现成 API**：`TextButton` 只有 `onClick`；`combinedClickable` 要另起一个可点节点，而 Main 传递
 * 是**内层先**——内层按钮会先拿到那一下抬起 ⇒ 长按也会弹出菜单。Initial 传递上接管才能把那一串按下整段收回。
 *
 * **点按完全不受影响**：未到分界时一个事件都不消费。分界值取平台量
 * `ViewConfiguration.longPressTimeoutMillis`（本模块不写死）；「按住不动、没有事件可推」那一段靠超时补
 * （成立之后不再计时，只等这一串按下结束）。
 */
private suspend fun PointerInputScope.detectLongPress(onLongPress: () -> Unit) {
    val gesture = LongPressGesture(timeoutMillis = viewConfiguration.longPressTimeoutMillis)
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gesture.down(down.uptimeMillis)
            var pressed = true
            while (pressed) {
                val event = if (gesture.longPressed) {
                    awaitPointerEvent(PointerEventPass.Initial)
                } else {
                    withTimeoutOrNull(gesture.waitMillis) { awaitPointerEvent(PointerEventPass.Initial) }
                }
                if (event == null) {
                    if (gesture.onTimeout()) onLongPress()
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (gesture.advance(change.uptimeMillis)) onLongPress()
                // 长按已成立：这一串事件（含最后那一下抬起）都归长按，消费掉 ⇒ 按钮的点按作废（不弹菜单）
                if (gesture.longPressed) change.consume()
                pressed = change.pressed
            }
            gesture.release()
        }
    }
}
