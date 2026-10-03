# 现有 Anime4K 链条审计（仅核对，不修改策略）

质量取自 Anime4KManager.Quality：FAST=S、BALANCED=M（默认）、HIGH=L。它们不改变模式顺序，而是替换 CNN 模型文件后缀。

| 模式 | Clamp_Highlights 后的核心顺序 |
| --- | --- |
| A | Rq → Uq → D → Uq |
| B | Sq → Uq → D → Uq |
| C | Nq → D → Uq |
| A_PLUS | Rq → Uq → D → Rq → Uq |
| B_PLUS | Sq → Uq → D → Sq → Uq |
| C_PLUS | Nq → D → Rq → Uq |
| ARTCNN | Ani4Kv2_ArtCNN_C4F32_i2_CMP.glsl |
| X | Rq（仅修复原分辨率，不做 CNN 放大；仍先执行 Clamp_Highlights） |
| OFF | 无 Anime4K 链 |

q 对应 S/M/L，文件名完整展开：

- H：Anime4K_Clamp_Highlights.glsl（所有非 OFF 模式先执行）。
- Rq：Anime4K_Restore_CNN_q.glsl。
- Sq：Anime4K_Restore_CNN_Soft_q.glsl。
- Uq：Anime4K_Upscale_CNN_x2_q.glsl。
- Nq：Anime4K_Upscale_Denoise_CNN_x2_q.glsl。
- D：Anime4K_AutoDownscalePre_x2.glsl。

核心之后，依照开关依次追加 Deblur → Darken → Thin：

| 质量 | CNN 后缀 | Darken 开启时 | Thin 开启时 |
| --- | --- | --- | --- |
| FAST | S | Anime4K_Darken_Fast.glsl | Anime4K_Thin_Fast.glsl |
| BALANCED | M | Anime4K_Darken_HQ.glsl | Anime4K_Thin_HQ.glsl |
| HIGH | L | Anime4K_Darken_HQ.glsl | Anime4K_Thin_HQ.glsl |

Deblur 始终是 Anime4K_Deblur_DoG.glsl。三种后处理开关在 DecoderPreferences 中默认均为 false（不能以 manager 成员初始值判断播放默认）。ARTCNN 的核心文件不随质量档位变化，质量只影响追加的 Darken/Thin。

## 当前启用条件与实际降级

- 总开关默认 false，模式默认 OFF；两者都允许才启用。
- legacy gpu 可以执行；gpu-next 需要 Vulkan。
- glsl-shaders 已由配置文件接管时，遵循现有配置接管规则。
- 未开启“4K 中启用”且像素数 ≥ 3840×2160 时关闭 Anime4K。
- selectRuntimeStableAnime4K 发现本项目热状态 headroom < 0.40，或累计 droppedFrames ≥ 15 / delayedFrames ≥ 25 / mistimedFrames ≥ 40 的任一项，会强制降为 C + FAST，因此用户选 HIGH 不保证实际一直使用 L。
- applyAnime4KShaderChain 把当前内置 Anime4K 链放在保留的其他 shader 前面；缺少所需文件时不提交链条。

审计来源：domain/anime4k/Anime4KManager.kt，preferences/DecoderPreferences.kt，ui/player/anime4k/Anime4KPlayback.kt，ui/player/MPVView.kt。此轮只修复 WebDAV 搜索与书签星入口，不修改上述 Anime4K 策略。

2026-10-04：按用户要求新增 X 链，质量仍映射 S/M/L，后处理沿用现有独立开关。热状态/掉帧降级时 X 仅降为 X/FAST，避免降到包含 Upscale 的 C 链。新增模式不依赖 mpv.conf 着色器配置。
