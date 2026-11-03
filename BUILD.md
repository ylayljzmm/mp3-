# 如何编译 APK（零基础版）

APK 不能像 zip 一样直接「生成」，必须用 **Android 编译工具**打包。  
你的电脑上目前没有安装 Java / Android SDK，所以我在这里没法替你直接产出 APK 文件。

下面两种方式任选一种，**推荐方式一（GitHub 自动编译）**，不用懂编程。

---

## 方式一：GitHub 自动编译（推荐，免费）

适合：不想装 Android Studio，只要能下载 APK。

### 第 1 步：注册 GitHub

打开 https://github.com 注册账号（有账号可跳过）。

### 第 2 步：把项目上传到 GitHub

1. 登录 GitHub，右上角 **+** → **New repository**
2. 仓库名填：`mp3-`，选 **Private** 或 Public，点 **Create repository**
3. 按 GitHub 页面提示，把本地文件夹 `F:\WorkBuddy\cheji\mp3-` 上传上去  
   - 不会用 Git 的话：在仓库页点 **uploading an existing file**，把 `mp3-` 文件夹里**所有文件**拖进去上传

### 第 3 步：触发自动编译

1. 打开你的仓库 → 顶部 **Actions**
2. 左侧选 **Build APK**
3. 右侧 **Run workflow** → 再点绿色 **Run workflow**
4. 等约 5～10 分钟，出现绿色 ✓ 表示成功

### 第 4 步：下载 APK

1. 点进刚跑完的那次任务
2. 页面底部 **Artifacts** → 下载 **MP3播放器-apk**
3. 解压得到 `app-debug.apk`，拷到 U 盘装车机

---

## 方式二：Android Studio 本地编译

适合：愿意装一个软件，以后自己改代码、自己编译。

### 第 1 步：下载安装 Android Studio

1. 打开：https://developer.android.com/studio  
2. 下载 Windows 版，一路 **下一步** 安装（默认选项即可）  
3. 首次打开会下载 SDK，**耐心等**（可能 10～30 分钟）

### 第 2 步：打开项目

1. Android Studio 启动页 → **Open**
2. 选文件夹：`F:\WorkBuddy\cheji\mp3-`
3. 点 **OK**
4. 底部若提示 **Gradle Sync**，等它跑完（第一次较慢）

### 第 3 步：编译 APK

1. 顶部菜单 **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**
2. 右下角弹出 **APK(s) generated successfully**，点 **locate**
3. 打开文件夹里的 **`app-debug.apk`**

默认路径：

```
F:\WorkBuddy\cheji\mp3-\app\build\outputs\apk\debug\app-debug.apk
```

### 第 4 步：装到车机

- **U 盘**：复制 apk 到 U 盘，车机文件管理器里点击安装  
- **ADB**（电脑已连车机时）：

```bash
adb install -r app-debug.apk
```

---

## 常见问题

**Q：你为什么不能直接给我 APK？**  
A：APK 需要在本机或云端用 Android 编译器打包。当前 Cursor 所在电脑没有安装这些工具，我只能写好源码和自动编译配置，不能凭空变出安装包。

**Q：Build 报错怎么办？**  
A：把 Actions 里红色报错那几行复制发给我。常见原因见下方「上传文件注意事项」。

**Q：每次只改几个文件上传，为什么总编译失败？**  
A：见下方专门说明。

**Q：安装时提示「未知来源」？**  
A：在车机设置里允许该来源安装应用（DiLink 一般在应用管理或安全设置里）。

**Q：GitHub Actions 要花钱吗？**  
A：个人仓库每月有免费额度，编译这个小项目足够用。

---

## 上传文件注意事项（重要！）

用 GitHub 网页「Add files via upload」更新代码时，**最容易踩坑**：

### 1. 路径必须带 `app/` 前缀

| 错误 ❌ | 正确 ✅ |
|--------|--------|
| 上传到仓库根目录 `VinylRecordView.kt` | `app/src/main/java/com/car/mp3player/ui/VinylRecordView.kt` |
| 根目录 `build.gradle.kts`（会覆盖整个项目配置） | `app/build.gradle.kts` |

根目录的 `build.gradle.kts` 只能有插件声明，**不要**用 app 里的内容覆盖它。

### 2. 一次更新要上传「成套」文件

改了一个功能，相关文件要**一起上传**，不能只传一两个。例如改动播放页时至少要传以下文件：

```
app/build.gradle.kts
app/src/main/java/com/car/mp3player/MainActivity.kt
app/src/main/java/com/car/mp3player/ui/MainHost.kt
app/src/main/java/com/car/mp3player/ui/AppThemeManager.kt
app/src/main/java/com/car/mp3player/ui/PlayerFragment.kt
app/src/main/java/com/car/mp3player/ui/VinylRecordView.kt
app/src/main/java/com/car/mp3player/ui/LyricRenderer.kt
app/src/main/java/com/car/mp3player/model/LyricThemePreset.kt
app/src/main/res/layout/activity_main.xml
app/src/main/res/layout/view_vinyl_record.xml
app/src/main/res/values/colors.xml
app/src/main/res/values/themes.xml
app/src/main/res/values-night/colors.xml
app/src/main/res/drawable/ic_launcher_foreground.xml
```

漏传 `MainHost.kt` 但传了 `MainActivity.kt` → 报「未实现接口方法」。  
漏传 `view_vinyl_record.xml` 但传了 `VinylRecordView.kt` → 报「找不到 vinylRotateGroup」。

### 3. 以前几次报错的真实原因（已修）

| 版本 | 报错原因 |
|------|----------|
| v3.3.0 | `VinylRecordView.kt` 写了 `ROTATION` 应为 `View.ROTATION` |
| v3.3.1 | `LyricRenderer.kt` 多写了一行不存在的 `nextPaint` |

**当前版本（v4.4.1）由 GitHub Actions 编译并签名通过**，代码本身没问题。

### 4. 上传后确认版本号

打开 GitHub 上 `app/build.gradle.kts`，确认 `versionName` 是你期望的版本（当前为 `4.4.1`），再 Run workflow。
改版本号时记得把 `versionCode` 也加 1，否则车机端的应用内更新不会认为这是新版本。

---

## 装上车之后

1. 音乐放到 `/sdcard/Music/`，歌词同名 `.lrc`  
2. 打开 App → 扫描音乐 → 播放  
3. 开启悬浮窗权限 + 悬浮歌词  
4. 极速模式白名单加入本应用  

详细用法见 [README.md](README.md)
