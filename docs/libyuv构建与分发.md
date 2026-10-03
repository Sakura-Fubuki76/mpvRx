# libyuv 构建与分发

libyuv 是 app 的 CMake 原生依赖，不是独立 Gradle 模块或仅在本机存在的二进制。

- 源码：`app/src/main/cpp/third_party/libyuv`，上游版本记录于 `README.mpvrx.md`。
- 上游 LICENSE、PATENTS、AUTHORS 保留；LICENSE/PATENTS 和来源记录也打包到 APK 的 `assets/licenses/libyuv`。
- JNI 桥接：`app/src/main/cpp/yuv/yuv_to_bitmap.cpp`，由 app 根 CMake 定义 `mpvrx_yuv`，链接源码构建的 `yuv` 静态库。
- Gradle：`app/build.gradle.kts` 已声明 externalNativeBuild，NDK 27.3.13750724、CMake 3.22.1；标准 APK 构建自动编译并打包 `libmpvrx_yuv.so`。
- GitHub 工作流调用正常 assemble 任务，沿用上述 CMake 构建，无需上传手工生成的 `.so` 或本机 NDK。

2026-10-04 验证：已通过项目原有 configureCMakeDebug/buildCMakeDebug 和 assembleStandardDebug 构建 arm64-v8a APK，确认 APK 含 libmpvrx_yuv.so。此前独立编译也验证过四种 ABI；本轮完整 APK 原生构建仅验证 arm64-v8a。59 项单元测试通过。

本机 SDK 的 NDK 目录为空，本次仅用忽略目录中的 init 脚本指定已有 NDK 路径和测试包名；未禁用 externalNativeBuild、未注入手工 YUV 二进制。`build/cloud-checks` 下 NDK、init 脚本、日志和 APK 是本机验证产物，不提交。

## 16KB 构建修正

标准 CMake 全目标构建曾额外生成并让 AGP 收集 libyuv.so（4KB LOAD 对齐），而真正的 JNI libmpvrx_yuv.so 以及其余 18 个原生库都为 16KB。该共享库未被任何 APK 原生库的 DT_NEEDED 引用，JNI 使用静态 yuv。现限制 externalNativeBuild 目标为 ytdl_wrapper/qjs_runtime/mpvrx_yuv，明确排除 APK 中多余 libyuv.so，同时根 CMake add_link_options 将 16KB 链接参数覆盖到子项目共享目标，避免只覆盖自有 JNI 目标。

yume 当前通过 Maven 引用 io.github.sakurafubuki.yume:yume-lib；yume-lib 的下载脚本 git clone libyuv 源码到 buildscripts/deps，再由 CMake add_subdirectory 构建。当前 .gitmodules 为空，不能将其描述为 Git 子模块。Git 子模块管理源码来源，Gradle 库模块管理 Android 构建和发布；两者不替代 ELF 对齐配置。
