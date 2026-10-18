# Comic-Viewer

一款按个人使用习惯定制的安卓漫画阅读器，支持连接SMB、WebDav、Komga。

## 浏览

- 条目形态：列表 / 网格 2 列 / 3 列 / 4 列
- 排序：名称、修改时间、发布时间，各带正向反向
- 书柜按连接分柜，陈列每个连接的根条目

## 阅读

- 两种模式：条漫（垂直连续滚动）、单页（LTR / RTL）
- 触摸区域纵向三等分：左边上一页、中间预览菜单、右边下一页
- 预览图菜单：查看预览图、滑动跳转页数、上/下一本
- 图像放大：双击/手势，放大后支持继续移动位置
- 鼠标：左右键点击、滚轮滚动翻页、侧键返回

## 设置

- 页面旋转：竖屏 / 横屏 / 跟随系统
- 主题：深色 / 浅色跟随系统，可手动覆盖
- 启动页面：上次阅读的位置 / 上次停留的位置 / 书柜 / 阅读器 / 首页

## 支持格式

jpg、jpeg、png、webp、CBZ、ZIP压缩包、图片文件夹

## 构建

要求：JDK 17+、Android SDK（compileSdk 34）

```bash
./gradlew assembleRelease
```

## 技术栈

Kotlin + Jetpack Compose（Material 3）、Room + KSP、Coroutines
minSdk 26（Android 8.0）/ targetSdk 34
Gradle 8.9 wrapper，侧载分发

## 许可证

[MIT](LICENSE)
