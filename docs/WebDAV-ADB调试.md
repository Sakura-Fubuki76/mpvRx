# WebDAV / AList 真机链路调试

测试包：`app.gyrolet.mpvrx.cloudtest.debug`，设备：`f1ead01e`。

日志版安装后，由测试者手动彻底关闭并重新打开测试版，再分别进入普通 WebDAV 和百度网盘 API 存储。打开子目录不应出现根任务被取消；下拉刷新会主动重启根扫描。记录两种存储对应的连接 ID。

```powershell
adb -s f1ead01e logcat -v threadtime -s CloudTrace:D CloudBatch:D
```

只保留不会打印账号、原始文件名、签名 URL 的诊断事件：

```powershell
adb -s f1ead01e logcat -v threadtime -s CloudTrace:D > webdav-trace.log
```

| 事件 | 应检查的内容 |
| --- | --- |
| startup.begin / startup.saved | 冷进程启动出现一次，autoConnectCount 与已勾选的存储一致 |
| startup.connect.begin / success / failure | 按 connection ID 查看握手是否成功，失败记录异常类型 |
| browse.load | 进入存储或刷新时出现，force=true 表示主动刷新 |
| storage.schedule / skip | 根任务是否入队；skip 区分 running 与 fresh_complete |
| directory.begin / result | 每个目录的 pathKey、条目数和失败类型，不能只看到当前目录 |
| alist.list / dav.list.fallback | API 目录成功还是回退普通 WebDAV |
| folder.summary | complete=true 且 videos=0 才能判定空目录 |
| storage.enumerated | 根目录枚举得到的全部文件数及是否完整；streaming=true 时后台 batch.begin 应先于枚举结束出现 |
| batch.begin / metadata.result / batch.end | 前台与后台任务数量，以及真实 durationReady / thumbnailReady / ready 数量，processed 不代表成功 |
| metadata.reject | 缺少当前目录行或文件版本不匹配导致的结果丢弃 |
| view.snapshot | 页面实际收到的有效时长数、空目录数和 hideEmpty 开关 |
| stream.api / stream.dav | 视频读取走 API 直链还是 WebDAV 回退 |
| http.media | 百度请求应为 baidu=true、uaBaidu=true；非零 Range 应收到 206 |
| storage.cancelled / failed | 导航到子目录不应取消根扫描；编辑/删除连接、主动刷新或策略变化会取消旧任务 |

完整枚举后的任务有 30 分钟冷却，避免每次切换目录都重新扫描。未完整扫描的目录不能作为隐藏空目录的依据。目录遍历有 5000 目录、64 层防循环限制。

`pathKey` 是诊断用短哈希，方便关联同一文件的事件，不是缓存文件名。CloudTrace 只在 Debug 构建输出。CloudBatch 的旧异常堆栈应仅在本机分析。

若设备过滤 Debug 级别，本诊断事件使用 Info 级别，并同时写入测试版私有文件（2 MiB 自动轮转），可直接读取：

```powershell
adb -s f1ead01e exec-out run-as app.gyrolet.mpvrx.cloudtest.debug cat files/cloud-debug.log
```

安装日志版后需由测试者启动应用以初始化文件；文件包含进程启动至后台扫描的全部 CloudTrace 事件。应用不运行时不会产生新日志。

本轮额外事件：`native.yuv.loaded` 表示原生库是否实际加载，`native.yuv.check` 是测试版原生自检结果，`sprite.progress` 是逐批发布的预览格数；`player.assets.begin/end` 与 `player.core.begin/end` 区分素材准备和播放器初始化耗时，`fonts.select` 记录字体库数量、请求/匹配数量与选择耗时。原有事件不输出字体名或视频名。

新增播放诊断（测试版）：

- `fonts.media.begin/index/probed/end`：区分前台 cachedOnly 与后台网络字体探测；parserCheck 必须为 true。
- `player.font.gate`：启动前字体耗时；`player.load.command` → `player.file.loaded` → `player.playback.restart` 的时间戳区分提交加载、文件就绪与画面恢复。
- `fonts.family`：字体族名及匹配文件数量（字体名称可见，不包含字幕正文或文件路径）；`fonts.applied/background.applied`：实际目录文件数量和 ASS override。
- `subtitle.match/added/fonts.failed`：匹配与添加成功并不代表字体加载成功。
- `gesture.subtitle.seek/timeline.seek`：实际进入的横滑分支及章节索引。
- `sprite.batch/decode/color/frame/failed`：批量下载与解码计数、解码器、crop、平面 row/pixel stride、色彩标准/范围、输出大小与回退错误。native=true 仅证明当前转换使用原生路径，不证明画面质量已验收。

本轮之前的日志已证明 Android ASS 解析抛出 PatternSyntaxException，而字幕添加成功；修复版不再使用该正则。旧雪碧图缓存版本已升级，修复版会重新生成。

搜索/恢复播放专项日志：search.begin 记录 queryKey/长度与代次，search.sources 对比 api/cached/merged 及 normalized 数量，search.result 记录最后发布数量；不记录查询原文。player.click/launch/load.options/font.gate/load.command/file.loaded/playback.restart 区分点击、准备、恢复起点与 mpv 加载。proxy.connect.begin/end、proxy.open、proxy.first.byte 记录连接、Range offset、打开/首字节总耗时及结果，并使用 pathKey 对齐同一视频。

## 2026-10-04 两次播放对比

用户确认搜索结果不再消失。私有日志中 connection=2 的两次播放：

| 阶段 | 已播放（恢复 233 秒） | 未播放 |
|---|---:|---:|
| 点击→启动 Activity | 10ms | 4ms |
| 前台字体准备 | 28ms | 12ms |
| loadfile→file-loaded | 1087ms | 2190ms |
| file-loaded→playback-restart | 5678ms | 396ms |
| 点击→playback-restart | 7069ms | 2637ms |

首个视频 pathKey=9c398da4，第二个 379c8ca8。首个视频恢复点 Range offset=125904669 的首字节仅 322ms，早于 playback-restart 约 5.4 秒；不能把整个额外等待归因于该次 Range 打开。外置字幕引用字体日志出现在恢复播放之前，但没有旧日志证明暂停门具体在哪一阶段阻塞。新增 player.restore.begin/database/applied/release、subtitle.restore.begin/name/fonts/command/end，用于区分数据库、名称解析、字体准备、sub-add 与暂停释放；不记录字幕 URI 或认证信息。playback-restart 是 mpv 事件，不能直接等同用户实际看到首帧的时间。

本次再播放：恢复 259 秒，network=false，externalCount=0；file-loaded=1791045042347，恢复暂停释放=1791045042492，restart=1791045049423。字幕恢复不是该次约 6.9 秒额外等待的原因。修复历史/队列入口的云端 connection/path 丢失，并增加 player.buffer.state（paused-for-cache/seeking）、player.native.restart（原生事件进入时刻）、player.render.begin/end；与 Activity 的 restart 对比可判断事件转发延迟，缓冲状态可判断 mpv 缓冲等待。仍需新版日志证明等待根因，未调整 cache-pause 或跳过初始化。
