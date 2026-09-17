# 启动期状态用 SharedPreferences，而非 SPEC 所述的 Room

启动落地判定（`core/nav/StartupRouting.kt`）发生在 NavHost 建立之前、必须在落地导航前**同步**得出（读取时点见下方票 #26 r2 注记），因此「上次阅读的位置 / 上次停留的位置」所需的少量状态（connId、containerId、上次退出时是否正在看书）落在 `startup` SharedPreferences（`ui/StartupStore.kt`），而不是 SPEC Implementation Decisions 所述的 Room。Room 的查询是挂起函数；改用它就得额外引入一层同步缓存，收益不足。

（票 #29 起排序方式不再属于启动期状态：它是全 app 一份的**排序设置**，与浏览位置无关，同样落 SharedPreferences——`ui/SortSettingStore.kt`。）

（票 #26 r2 起读取时点从「首帧组合期」挪到启动 effect（`ui/AppNav.kt`）的第一句：仍在**第一次挂起之前同步读完**，只是不再占用组合体。原因：同一帧里另一个 effect（`LaunchedEffect(currentRoute)`）会写 `was_reading`，跨线程的「IO 读 / 主线程写」没有先后保证，只有「本会话的读先于本会话的任何写」才稳定——否则故事 47 的续读判定会与写入竞态。存储仍为 prefs，排序仍不属启动期状态；本 ADR 的结论不变：必须是一次可同步完成的读。）

这是**有意的偏离**：阅读进度、连接配置仍全部在 Room。若要改回 Room，必须先解决「落地判定前同步读（且先于本会话对该状态的任何写）」这一约束。

Status: accepted
