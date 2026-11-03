# MP3 播放器 CloudBase 更新服务（已停用）

> **现状：客户端已不再使用本服务。**
> v4.4.1 起，应用内更新直接读取本仓库的 GitHub Releases「最新正式版」
> （见 `app/src/main/java/com/car/mp3player/update/AppUpdateManager.kt`），
> 发版即生效，无需再部署云函数或手动更新版本号、SHA-256。
>
> 本目录仅作为历史实现保留，可整目录删除；`mp3Update` 云函数与 `/mp3` 路由
> 即使继续存在也不会再被调用。

---

以下为原有说明，仅供备查：

- 环境：`chuya-d6gyub7awb35a8bf7`
- 函数：`mp3Update`
- 安装包：`releases/mp3-latest.apk`
- HTTP 路由：`mp3`

发布时必须先使用原车机安装包相同的调试证书签名 APK，再更新函数中的
SHA-256 与文件大小，最后部署函数和 `/mp3` 路由。客户端会同时校验大小与
SHA-256，校验失败时不会拉起安装器。
