package com.cc3301.comicviewer.core.touch

/**
 * 按下点纵坐标是否落在底边系统手势带内（spec §底边系统手势带）。
 *
 * 带贴着屏幕底边，带高从底边向上量；[y] 是按下点从节点顶边向下量的纵坐标，[heightPx] 是节点高。
 * 只看纵坐标：落在带内（含带的上边界）的整次按下不算点击，与横坐标落在哪个触摸区无关。
 * 带高取 `WindowInsets.systemGestures` 的底部 inset，由界面侧按当前密度折成像素后传入。
 */
fun inBottomGestureStrip(y: Float, heightPx: Float, stripHeightPx: Float): Boolean =
    y >= heightPx - stripHeightPx
