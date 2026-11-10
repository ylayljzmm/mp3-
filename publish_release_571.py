import json, os, sys, re, urllib.request, urllib.error, urllib.parse

# 复用仓库既有发布脚本里的写权限令牌（不在本文件重复写一份，避免多副本）
_SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "publish_release.py")
with open(_SRC, encoding="utf-8") as _f:
    TOKEN = re.search(r'TOKEN = "([^"]+)"', _f.read()).group(1)

REPO = "ylayljzmm/mp3-"
APK_PATH = r"F:/WorkBuddy/cheji/mp3-player-5.7.1-signed.apk"
TAG = "v5.7.1"

H = {"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
     "X-GitHub-Api-Version": "2022-11-28"}


def req(method, url, data=None, extra=None):
    headers = dict(H)
    if extra:
        headers.update(extra)
    r = urllib.request.Request(url, data=data, method=method, headers=headers)
    return urllib.request.urlopen(r, timeout=400)


if not os.path.exists(APK_PATH):
    print("ERROR: apk not found:", APK_PATH)
    sys.exit(1)
print("[1] apk size:", os.path.getsize(APK_PATH))

BODY = """随声 5.7.1

## 更新内容（I7 七项改造）

1. **车机左列右移**：播放页左列整体右移，与右侧歌词栏更均衡，视线不再偏左（车机横屏生效，不影响手机竖屏）。
2. **黑胶与封面一起顺时针旋转**：唱片与中间专辑图作为一个整体同向旋转；设置页新增「封面随黑胶一起旋转」开关，关闭即回到封面保持正立。
3. **车机浮层修复**：队列、右上角菜单、歌曲信息、歌词调节、音效等浮层改为全屏宽，并正确扣除系统状态栏/底部栏，内容区完整可见、可滚动。
4. **抽屉精简**：删除抽屉顶部图标/应用名/版本栏，打开即显示从「媒体库」开始的列表（车机与手机同步）；版本信息仍可在「关于」页查看。
5. **合并歌曲信息入口**：车机横屏删除与「歌曲信息」按钮重复的左滑信息页，将其技术字段（格式、码率、采样率、位深、文件大小等）并入「歌曲信息」面板；竖屏三页不变。
6. **冷启动体验优化**：进程启动即预热媒体会话与落盘队列，未进入 App 时方向盘/蓝牙/语音即可起播；不再需要等待全库扫描（不改现有播放逻辑）。
7. **扫描提示静默**：进入 App 时的「扫描完成」提示不再弹出；手动点扫描仍保留完成反馈。

## 安装

- 本 APK 与历史版本共用同一张签名证书，升级可直接覆盖安装，无需卸载旧版。
- 若车机网络访问 GitHub 不便，可在手机或电脑打开本页下载 APK 后拷贝安装。

## 校验

- versionCode = 60
- versionName = 5.7.1
"""

print("[2] creating release", TAG, "...")
payload = {
    "tag_name": TAG,
    "target_commitish": "main",
    "name": "随声 5.7.1（左列右移 / 封面随盘顺时针转 / 浮层修复 / 抽屉精简 / 冷启动即播）",
    "body": BODY,
    "draft": False,
    "prerelease": False,
}

release = None
try:
    r = req("POST", f"https://api.github.com/repos/{REPO}/releases",
            data=json.dumps(payload).encode("utf-8"))
    release = json.load(r)
    print("    created id:", release["id"])
except urllib.error.HTTPError as e:
    b = e.read().decode("utf-8", "replace")
    print("    HTTP", e.code, b[:400])
    if e.code == 422:
        r = req("GET", f"https://api.github.com/repos/{REPO}/releases/tags/{TAG}")
        release = json.load(r)
        print("    reusing existing id:", release["id"])
    else:
        sys.exit(1)

upload_url = release["upload_url"].split("{")[0]
print("[3] uploading asset ...")
asset_name = "MP3播放器-5.7.1.apk"
with open(APK_PATH, "rb") as f:
    data = f.read()
try:
    r = req("POST",
            f"{upload_url}?name={urllib.parse.quote(asset_name)}&label={urllib.parse.quote(asset_name)}",
            data=data,
            extra={"Content-Type": "application/vnd.android.package-archive"})
    asset = json.load(r)
    print("    uploaded:", asset.get("browser_download_url"))
except urllib.error.HTTPError as e:
    b = e.read().decode("utf-8", "replace")
    print("    upload HTTP", e.code, b[:400])
    sys.exit(1)

print("\nDONE ->", release.get("html_url"))
