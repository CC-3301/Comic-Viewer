package com.cc3301.comicviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc3301.comicviewer.core.view.ReaderMenuLayout

/** 跳页弹窗卡片底色：比半透明面板实一档，压在阅读页上仍看得清 */
internal const val JUMP_DIALOG_SURFACE_COLOR: Long = 0xFF2B2B2B

/** 跳页弹窗输入框未聚焦时的描边色（聚焦时换成仓库强调橙） */
internal const val JUMP_DIALOG_BORDER_COLOR: Long = 0xFF8A8A8A

/** 跳页弹窗灰字底文色（底文 = 当前页 / 总页数） */
internal const val JUMP_DIALOG_PLACEHOLDER_COLOR: Long = 0xFF9A9A9A

/** 跳页弹窗「确定」置灰时的字色（输入不在 `1..总页数` 内） */
internal const val JUMP_DIALOG_DISABLED_COLOR: Long = 0xFF5A5A5A

/**
 * 跳页弹窗：点阅读菜单底部的页数打开，输入页码 → 确定 → 跳到该页。
 *
 * **遮罩完全透明**：不做任何压暗（阅读页保持可见，与阅读菜单面板「不铺满全屏深色遮罩」同一口径）。
 * 遮罩自己接点击 ⇒ 点弹窗外任意处 = 关闭弹窗、不跳页。
 *
 * 只做一件事，所以刻意做得小：宽度按屏宽算（[ReaderMenuLayout.jumpDialogWidthDp]，
 * 大屏 ≈ 屏宽 1/3、小屏落到 150dp 下限），标题 / 输入框 / 确定三行居中，没有「取消」按钮
 * （点外面就是取消，多一个按钮多一个误触点）。
 *
 * 打开时输入框**不自动聚焦**：光标不落进去、软键盘不弹，用户点一下输入框才出数字键盘。
 */
@Composable
internal fun JumpToPageDialog(
    currentPage: Int,
    pageCount: Int,
    viewportWidthDp: Float,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 输入框内容只活在本弹窗的生命周期里：弹窗每次打开都重新进组合，因此下次打开恒为空
    var input by remember { mutableStateOf("") }
    JumpToPageDialogContent(
        input = input,
        currentPage = currentPage,
        pageCount = pageCount,
        viewportWidthDp = viewportWidthDp,
        onInputChange = { raw -> input = ReaderMenuLayout.jumpDialogSanitizeInput(raw) },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * 跳页弹窗的**无状态**本体：输入内容由调用方持有（生产是 [JumpToPageDialog] 的 `remember`，
 * 用例传一个可控的值）。分开的理由与 `ui/` 其余组件一致——量「点了会怎样」不必先会打字。
 *
 * [onConfirm] 收到的是 **0-based 页位**（换算在 [ReaderMenuLayout.jumpDialogPageIndex] 一处）。
 * 输入不合法时它不会被调用：「确定」置灰，回车同样不生效。
 */
@Composable
internal fun JumpToPageDialogContent(
    input: String,
    currentPage: Int,
    pageCount: Int,
    viewportWidthDp: Float,
    onInputChange: (String) -> Unit,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 合法与否只有这一个判据：按钮可用态、置灰色、回车是否生效都读它
    val targetIndex = ReaderMenuLayout.jumpDialogPageIndex(input, pageCount)
    val confirm: () -> Unit = { if (targetIndex != null) onConfirm(targetIndex) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 透明遮罩：只接点击、不压暗。祖先（面板的 detectTapGestures、背景的「点空白关菜单」）
            // 收不到这次点按，因此点弹窗外只关弹窗、不会连带把菜单关掉
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ReaderMenuLayout.jumpDialogWidthDp(viewportWidthDp).dp)
                .background(
                    color = Color(JUMP_DIALOG_SURFACE_COLOR),
                    shape = RoundedCornerShape(ReaderMenuLayout.JUMP_DIALOG_CORNER_DP.dp),
                )
                .padding(ReaderMenuLayout.JUMP_DIALOG_PADDING_DP.dp)
                // 吞掉卡片内点击：否则点卡片空白处会穿透到遮罩、把弹窗关掉
                .pointerInput(Unit) { detectTapGestures { } },
            horizontalAlignment = Alignment.CenterHorizontally,
            // 行距只在这里给一处：四边内边距 + 两条行距 ⇒ 标题上方留白与确定下方留白相等
            verticalArrangement = Arrangement.spacedBy(ReaderMenuLayout.JUMP_DIALOG_ROW_GAP_DP.dp),
        ) {
            // 标题行：可见高与下面的确定行同高，两行占的上下空间因此相等
            Box(
                modifier = Modifier.height(ReaderMenuLayout.JUMP_DIALOG_ROW_HEIGHT_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "跳转到",
                    color = Color.White,
                    fontSize = ReaderMenuLayout.JUMP_DIALOG_TEXT_SP.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }

            // 输入框：空的时候显示灰字底文「当前页 / 总页数」（格式与底部行页数同源），
            // 用户照底文就知道上限。不请求焦点 ⇒ 打开弹窗时键盘不弹
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ReaderMenuLayout.JUMP_DIALOG_FIELD_HEIGHT_DP.dp),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = ReaderMenuLayout.JUMP_DIALOG_TEXT_SP.sp,
                    textAlign = TextAlign.Center,
                ),
                placeholder = {
                    Text(
                        text = ReaderMenuLayout.pageLabelText(currentPage, pageCount),
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(JUMP_DIALOG_PLACEHOLDER_COLOR),
                        fontSize = ReaderMenuLayout.JUMP_DIALOG_TEXT_SP.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(4.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                // 键盘上的回车 = 点「确定」：两者回同一个 confirm，不存在「按钮能跳、回车不能」
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = ACCENT_ORANGE,
                    focusedBorderColor = ACCENT_ORANGE,
                    unfocusedBorderColor = Color(JUMP_DIALOG_BORDER_COLOR),
                ),
            )

            // 确定行：可见高与标题行同高；命中带 48dp 且**只向下挂**（向上会盖住输入框下缘、抢焦点）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ReaderMenuLayout.JUMP_DIALOG_ROW_HEIGHT_DP.dp),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .requiredHeight(ReaderMenuLayout.JUMP_DIALOG_CONFIRM_HIT_HEIGHT_DP.dp)
                        .offset(y = confirmHitDropDp())
                        .clickable(enabled = targetIndex != null, onClick = confirm),
                )
                Text(
                    text = "确定",
                    modifier = Modifier.align(Alignment.Center),
                    color = if (targetIndex != null) ACCENT_ORANGE else Color(JUMP_DIALOG_DISABLED_COLOR),
                    fontSize = ReaderMenuLayout.JUMP_DIALOG_TEXT_SP.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 确定行的命中层相对行中心要**下移**多少（dp）：`(命中高 − 行高) / 2`。
 *
 * 把「与行同心」的 48dp 命中带推到「顶边与行顶齐平」（只向下挂）——与底部行的 `hitDropDp` 同一算式，
 * 但两处行高不同（这里 24dp、那里 36dp），因此各留一份、不强行合并。
 */
private fun confirmHitDropDp(): androidx.compose.ui.unit.Dp =
    ((ReaderMenuLayout.JUMP_DIALOG_CONFIRM_HIT_HEIGHT_DP - ReaderMenuLayout.JUMP_DIALOG_ROW_HEIGHT_DP) / 2f).dp
