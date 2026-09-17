# 启动期状态用 SharedPreferences，而非 SPEC 所述的 Room

启动落地判定（`core/nav/StartupRouting.kt`）发生在 NavHost 建立之前、必须在首帧组合前**同步**得出，因此「上次阅读的位置 / 上次停留的位置」所需的少量状态（connId、containerId、上次退出时是否正在看书）落在 `startup` SharedPreferences（`ui/StartupStore.kt`），而不是 SPEC Implementation Decisions 所述的 Room。Room 的查询是挂起函数；改用它就得额外引入一层同步缓存，收益不足。

（票 #29 起排序方式不再属于启动期状态：它是全 app 一份的**排序设置**，与浏览位置无关，同样落 SharedPreferences——`ui/SortSettingStore.kt`。）

这是**有意的偏离**：阅读进度、连接配置仍全部在 Room。若要改回 Room，必须先解决「导航建立前同步读」这一约束。

Status: accepted
