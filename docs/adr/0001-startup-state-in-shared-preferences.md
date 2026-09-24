# 启动期状态用 SharedPreferences，而非 SPEC 所述的 Room

启动落地判定（`core/nav/StartupRouting.kt`）必须在**落地导航前的一次同步读**里得出（判定本身在中转页的启动 effect 内完成，读取时点见下方票 #26 r2 注记），因此「上次阅读的位置 / 上次停留的位置」所需的少量状态（connId、containerId、上次退出时是否正在看书）落在 `startup` SharedPreferences（`ui/StartupStore.kt`），而不是 SPEC Implementation Decisions 所述的 Room。Room 的查询是挂起函数；改用它就得额外引入一层同步缓存，收益不足。

（票 #29 起排序方式不再属于启动期状态：它是全 app 一份的**排序设置**，与浏览位置无关，同样落 SharedPreferences——`ui/SortSettingStore.kt`。）

（票 #26 r2 起读取时点从「首帧组合期」挪到启动 effect（`ui/AppNav.kt`）的第一句：仍在**第一次挂起之前同步读完**，只是不再占用组合体。原因：同一帧里另一个 effect（`LaunchedEffect(currentRoute)`）会写 `was_reading`；票 #26 r3 又把该写点限定在已离开中转页的路由上（`readingFlagToRecord`），加上「先同步读」两手，才保证本会话的读先于本会话的任何写——否则故事 47 的续读判定会与写入竞态。存储仍为 prefs，排序仍不属启动期状态；本 ADR 的结论不变：必须是一次可同步完成的读。）

（票 #137 起同一份 prefs 还存**顶层落点**（首页 / 书柜 / 设置，`last_top_level`）：写点挂在同一个 `LaunchedEffect(currentRoute)` 上，冷启动那一帧路由是 `startup` ⇒ 本会话对该键**零写**，所以上面「本会话的读先于本会话的任何写」这条结构性保证不变。）

这是**有意的偏离**：阅读进度、连接配置仍全部在 Room。若要改回 Room，必须先解决「落地判定前同步读（且先于本会话对该状态的任何写）」这一约束。

Status: accepted
