import json, os, re, sys, urllib.parse, urllib.request, urllib.error

# 复用仓库既有发布脚本里的写权限令牌（不在本文件重复写一份，避免多副本）
_SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "publish_release.py")
with open(_SRC, encoding="utf-8") as _f:
    TOKEN = re.search(r'TOKEN = "([^"]+)"', _f.read()).group(1)

REPO = "ylayljzmm/mp3-"
APK_PATH = r"F:/WorkBuddy/cheji/suisheng-5.8.0-signed.apk"
TAG = "v5.8.0"
ASSET_NAME = "随声-5.8.0.apk"

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

BODY = """随声 5.8.0

## 更新内容

### 一、播放列表：能看见「现在播到第几首」，能一步清掉文件

1. **打开队列直接就位**：列表自动滚到当前播放项，并把当前项放在偏上的位置（后面还有哪些一眼能看到）；头部从「共 N 首」改为「共 N 首 · 正在播放第 M 首」。
2. **移出后可撤销**：点 × 先把这首移出队列，下方出现操作条。「撤销」把它插回原来的位置（只保最近一首，关掉面板即失效），不会打断正在播的歌。
3. **移出后可删文件**：操作条上的「删除文件」进二次确认，**列出将被删除的文件名**（音频 + 歌词），歌词是否一起删可以勾选。音频没删成绝不会去删歌词。
4. 删除走系统授权：Android 11 及以上由系统弹窗确认后再删；Android 10 走可恢复权限；Android 9 及以下直接删。删除后会重新扫描曲库，并把这首歌从播放队列里摘掉。

### 二、歌词对得更准：唱到哪停在哪

5. **把「唱完」和「行尾」拆成两个时刻**。以前一行的时间长度等于「到下一行为止」，中间那段间奏/尾奏也被算成演唱时间、再平摊给每个字 —— 这就是前奏、间奏时最后一个字还在慢慢擦的原因。现在擦除停在这一行真正唱完的时刻，间隙期间画面完全静止。
6. **接上逐字轨**：向支持逐字的渠道额外请求逐字时间轴，每个字有自己的起止毫秒，逐字擦拭按真实时长走；拿不到逐字轨自动回落到逐行高亮。可在「设置 → 歌词设置 → 逐字优先」关闭（关掉能省一次较大的请求）。
7. 新增「前奏 / 演唱 / 间奏 / 尾奏」的统一判定，四条歌词消费链（播放页、悬浮歌词、投屏、媒体会话）共用同一份结果，不会出现「这页停住了、那页还在动」。

### 三、歌词来源可视化

8. 播放页「调」→「来源」页签：列出全部歌词渠道，每个渠道标出能力（**逐字 / 译文 / 时间轴 / 纯文本**），当前这份歌词来自哪里一目了然，点一下就从那个渠道重新取。
9. 顶部可选「自动（按顺序尝试）」，也可指定某一个渠道；被关掉的渠道会置灰但仍可临时点用。
10. **新增酷我音乐渠道**（联网歌词渠道增至 7 个），取不到时自动回落，不影响原有顺序。

### 四、歌词调节面板重做

11. 播放页顶栏新增常驻 **「调」** 按钮，不用再点「更多 → 歌词调节」两级。
12. 面板改为 **「同步 / 来源」双页签**：同步页保留偏移滑杆与 ±0.5s / ±1.0s 快捷档、译文与注音开关；来源页即上面第 8~10 条。
13. 长按歌词区仍然是「搜索歌词」，没有改动。

### 五、其他

14. 应用名与构建产物统一为「随声 / suisheng」，不再出现旧的内部命名。

## 安装

- 本 APK 与历史版本共用同一张签名证书，升级可直接覆盖安装，无需卸载旧版。
- 若车机网络访问 GitHub 不便，可在手机或电脑打开本页下载 APK 后拷贝安装。

## 校验

- versionCode = 63
- versionName = 5.8.0
"""

print("[2] creating release", TAG, "...")
payload = {
    "tag_name": TAG,
    "target_commitish": "main",
    "name": "随声 5.8.0（逐字歌词 / 队列两步删除 / 来源可视化 / 调节面板重做）",
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

# 同 tag 的 asset 已存在时先删掉，否则会变成两个同名附件
for a in release.get("assets", []):
    if a["name"] == ASSET_NAME:
        req("DELETE", f"https://api.github.com/repos/{REPO}/releases/assets/{a['id']}")
        print("    removed old asset", a["id"])

upload_url = release["upload_url"].split("{")[0]
print("[3] uploading asset ...")
with open(APK_PATH, "rb") as f:
    data = f.read()
try:
    r = req("POST",
            f"{upload_url}?name={urllib.parse.quote(ASSET_NAME)}&label={urllib.parse.quote(ASSET_NAME)}",
            data=data,
            extra={"Content-Type": "application/vnd.android.package-archive"})
    asset = json.load(r)
    print("    uploaded:", asset.get("browser_download_url"))
except urllib.error.HTTPError as e:
    b = e.read().decode("utf-8", "replace")
    print("    upload HTTP", e.code, b[:400])
    sys.exit(1)

print("\nDONE ->", release.get("html_url"))
