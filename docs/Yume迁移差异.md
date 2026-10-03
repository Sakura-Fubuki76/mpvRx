# yume 行为迁移核对

## 已撤掉的自行增加行为

此前增加的未完成雪碧图提前发布、180 秒总生成超时、3 秒启动延迟、50 MiB 自动修剪、WebP 质量 90、按最近成功时间紧凑寻址均已撤掉。它们没有先向用户说明，不能算行为一致的迁移。

横滑字幕跳对白来自 mpvRx 原功能，现按用户要求彻底删除运行分支及设置/搜索入口，不再靠章节名判断。

## 直接迁入的 yume 代码

源：yume/core/data/src/main/java/com/sakurafubuki/yume/core/data/repository/SpriteSheetGenerator.kt 与 BitmapSolidColor.kt。

目标：domain/cloud/YumeSpriteSheetGenerator.kt 与 YumeBitmapSolidColor.kt。

保留原生成器的完整行为：候选邻帧多轮重试、纯色回退、关键帧去重并用于多个格子、8 路下载批处理、批量 raw Image 共用解码器、YUV 两次 BOX 缩放、色彩转换、比例/旋转、短视频帧数、等间隔网格元数据、WebP 质量 80、本地 WebP+JSON 复用、独立后台 scope 和同视频共享 in-flight。处理完成且保存后才交给 UI，没有部分发布。

CloudSpriteRepository 已改为外围适配器，不再自写生成算法。旧缓存格式使用独立版本隔离，仅首次重新生成；以后沿用原生成器的缓存读取。

## mpvRx 必要适配

- 包名、依赖注入、日志接入现有项目；过滤原日志中的 URL/HTTP 头，保留计数与错误类型。
- WebDAV 使用鉴权代理与稳定连接/路径/文件版本身份。代理地址没有扩展名，因此单独传入真实容器类型，否则会误走 MediaExtractor。
- 生成任务不随播放器退出取消，代理也在实际生成结束后注销；新视频不会收到旧视频完成后的预览。
- MKV trackNumber 使用当前 parsed 索引中的值，替代源项目 extractor 成员；持久云元数据时长作为稳定输入。
- UI 使用 yume 的格子尺寸与 intervalMs 寻址；旧 JSON 仍由旧字段兼容解析，新生成一律使用等间隔网格。
- API 26–29 用旧 WEBP 枚举兼容，质量仍为 80。
- 保留先前已实测解决黑边的裁剪与显式 U/V 平面桥接适配；原生库真实加载已有日志证据。

## 卡片与字体

统一 VideoCard 读取持久播放身份与兼容旧身份的 Room Flow，本地、WebDAV、最近页面均显示已播放时间/总时长及进度，沿用原界面的显示开关。WebDAV 明确传入连接/路径身份，避免裸相对路径错误归入本地身份。原本调用处已有进度数据继续作为无持久记录时的回退。

字体按 yume 的 ASS Style/行内字体引用选择，适配 mpv/libass 的字体目录，不移植独立 AssRenderer。保留字体名称表索引和每媒体选中目录，前台不等待网络字体探测；实际 ASS 样式仍要以手机验证和 fonts.family/applied 日志为准，不能仅凭 selected 数量判定正确。

## 计划中其他适配

配置编辑器读取外部或内部已有 mpv.conf/input.conf；读取失败禁止保存，避免以空白误覆写。此前新增的 Anime4K 着色器链设置入口已按用户要求删除，现有预设链保持不变；其他适配仍暂停。

## 2026-10-04 剩余差异核对

### 缓冲与刷新策略结论修正

上一轮将 yume 带宽自适应/拖动缓冲以及 OpenList refresh 参数直接列为待适配，依据不足，撤回必迁结论。MPVView 的 64MiB 是应用默认前向解复用缓冲上限，不是整个 mpv 缓存体系，也不是不可修改的上限。MpvConfigOverride.NETWORK_BUFFERING 包含 cache、cache-pause、cache-pause-wait、demuxer-max-bytes 等；PlaybackSession.setOptionString 会跳过交由 mpv.conf 控制的选项。后向缓存等应用未设置的选项可由配置文件交给 mpv 管理。

保留 mpv 原有前后向缓存、缓存内 seek 和缓冲恢复机制，当前不迁入 yume 的 Media3 LoadControl 策略，也不新增自动调参层。仅在实际日志证明 mpv 现有机制或配置不足时再讨论。OpenList refresh=false 是使用服务端缓存的策略差异，不认定为缺陷；无实际服务端缓存失效问题时不强制 refresh=true。

验证应聚焦迁入代码的接缝：鉴权/直链刷新、代理 Range 与重连、字体、元数据身份及雪碧图任务生命周期。yume 的正常使用是原算法的有效依据，不要求重新证明全部算法；但不同代理、协程、文件入口及 mpv/libass 集成后的行为仍不能仅凭源项目正常就认定验收通过。

- yume PlayerService.kt 的 ScrubbingAwareLoadControl 按实测带宽调整缓冲并在拖动时切换阈值；mpvRx 采用 mpv 缓冲体系。两者实现不同，不再列为必须补齐的迁移项。
- OpenList 已接入登录/鉴权刷新、分页列目录、搜索、fs/get 直链及请求头。与 yume OpenListApi 相比，listDirectory 的 refresh 参数尚未贯通：当前 fs/list 固定 refresh=false；界面刷新不能据此宣称会强制刷新服务端目录缓存。独立 probeImageDimensions 接口未原样迁入，现有封面下载解码不能称作该接口的完整迁移。目录密码当前固定为空，不支持密码保护目录。
- 用户已确认本轮播放启动快且没有闪退。字体库移出 mpv 配置目录的默认 fonts 扫描位置是主要修复，另有自定义按钮脚本延后初始化；未做逐项隔离测试，不能把全部收益定量归因于字体。
- 雪碧图完整生成后发布和 WebP+JSON 复用已实现，包括普通本地文件入口。实际 ASS 字体样式、雪碧图最终质量及跨重启复用、本地/云端/最近卡片进度持久显示仍需完整真机验收；content URI 等入口也不能以普通文件路径测试代替。
- 弱网、长时间播放、连续前后 seek、直链/Token 过期与服务端重启恢复尚无完整回归验收证据。视频流字节磁盘缓存按用户要求排除，不列为未完成迁移。

## 验收

构建通过不等于全部真机行为验收。需要：生成完整后出现 sprite.saved，重新打开同视频出现实际 sprite.cache.hit；生成途中没有预览；本地/云端/最近卡片在退出播放后及重启后保留时间与进度；横滑不再显示下一句对白；非 16:9/旋转预览正确。未完成真机检查前不声称所有迁移已经验收。
