# 亮点 · 附注（给发安装包的人）

主说明在 [使用说明](liangdian-getting-started.md)。

| 项目 | 内容 |
|---|---|
| 版本 | 0.6（versionCode 2） |
| 源码 | `project/app-android/` |
| debug 包 | `app/build/outputs/apk/debug/app-debug.apk`，md5 `632a15c93653cfcabdf062c175885122` |
| release 包（已签名） | `app/build/outputs/apk/release/app-release-signed.apk`，md5 `294d6eb4dcac322acee8f05791896364` |

说明：

- debug 包含仅调试用的 `TEST_*` 广播通道，只发给可信的人；正式对外分发用 release。
- md5 以实际交付文件为准。
- 安装包需要 Android 8.0 及以上。
