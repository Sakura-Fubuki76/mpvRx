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
| storage.enumerated | 根目录枚举得到的全部文件数及是否完整 |
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
