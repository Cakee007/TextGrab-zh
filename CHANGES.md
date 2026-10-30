# TextGrab 中文版（截屏识字）

基于 [notune/TextGrab](https://github.com/notune/TextGrab) v1.1（MIT）修改的本地版本，
目标：控制中心一个磁贴 → 截屏 → 划选复制，且**支持中文**。

## 相对于上游的改动

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts` | `applicationId` 改为 `dev.noah.textgrab.zh`（可与原版共存安装）；版本 `1.2-zh`；依赖从 `text-recognition`（拉丁）换成 `text-recognition-chinese:16.0.1`（仅中文模型，含拉丁字母与数字识别）；启用 CI 环境变量签名 |
| `app/src/main/java/dev/noah/textgrab/OcrEngine.kt` | 识别器换为 `ChineseTextRecognizerOptions`；拼字逻辑增加 CJK 边界判断，中文之间不插空格，中英之间保留空格 |
| `app/src/main/java/dev/noah/textgrab/MainActivity.kt` | `processBitmap` 加异常兜底，识别出错时退出加载态并提示，而不是卡住 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 新增简体中文字符串，磁贴名称改为「截屏识字」 |
| `.github/workflows/build.yml` | GitHub Actions 云编译，签名后上传 APK |

## 使用前提

- Android 8.0（API 26）及以上；**磁贴自动截屏需要 Android 12+**（无障碍截屏 API）。
- Android 12 以下仍可用：先按音量减+电源截屏，再点磁贴读取最新截图。

## 编译（GitHub Actions）

1. 在 GitHub 新建一个空仓库，把本目录推上去（推 `main` 分支）。
2. 生成签名 keystore（本地执行一次，妥善保存，后续升级必须同一把）：
   ```sh
   keytool -genkeypair -v -keystore release.jks -alias textgrab \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
3. 把 keystore 转成 base64 并写入仓库 Secrets：
   ```sh
   base64 -w0 release.jks > release.jks.b64
   ```
   Secrets 需要四项：
   - `SIGNING_KEY_BASE64` → `release.jks.b64` 的内容
   - `SIGNING_STORE_PASSWORD`
   - `SIGNING_KEY_ALIAS`（示例中是 `textgrab`）
   - `SIGNING_KEY_PASSWORD`
4. 进入 Actions 页面，选择 **Build APK** → **Run workflow**。
5. 构建完成后在 Artifacts 下载 `TextGrab-zh-apk`，装到手机上。

> 没有配置 Secrets 也能跑，只是产出的是 debug 包（无需签名即可安装，但不可作为正式分发）。

## 安装后的一次性设置

1. 先打开一次 TextGrab 中文版，允许相册权限（读取最新截图用）。
2. 点主页的 **「开启一步截屏」**，在系统无障碍列表里启用 `TextGrab 一步截屏`。
   它只在点磁贴时截屏，不读屏幕内容、不监听输入、无网络权限。
3. 下拉控制中心 → 编辑（铅笔）→ 把 **「截屏识字」** 磁贴拖进常用区。

## 日常使用

- 任意界面下拉 → 点「截屏识字」→ 手指划选文字 → 复制，自动回原 App。
- 也可从任意 App「分享图片」给本应用，或从文件管理器「打开方式」选它。

## 已知限制

- 只内置中文模型：对简体/繁体中文效果最好，拉丁字母和数字也能识别，但纯德/法/越南语等带特殊变音符的文本精度不如原版的拉丁模型。
- APK 体积约 45–50 MB（模型约 20 MB）。
- 无障碍截屏在部分厂商 ROM（如某些 MIUI / EMUI 版本）可能被系统限制，此时会回退到打开最新截图。

## 1.3-zh —— 截屏链路的最终实现

经过多轮实测，ColorOS 上有三个坑，最后的解法都不是"猜时间"，而是把每一步做成可验证的状态：

1. **控制中心收不回去。** 磁贴回调里任何收起手段都被吞掉（`startActivityAndCollapse`、无障碍全局动作、`su -c "cmd statusbar collapse"`）。唯一有效的是一个获得焦点的窗口——透明 Activity 一出现，面板必然被顶走。
2. **截到的是控制中心。** 系统报告"面板已关闭"完全不可信：窗口不会从列表里消失，类型报成 `SYSTEM`，边界和激活状态也不变。改为**逐帧比像素**：每 40ms 截一帧，两帧一致即说明收起动画结束，留下的那帧必然不含控制中心。
3. **第二次用就套娃。** 上一次的文字选择界面还留在前台，被当成截屏目标。现在序列开头先发一次关闭请求，让 `MainActivity` 自行退出，系统里只剩目标应用。

最终顺序（每一步都等上一步完成）：点磁贴 → 透明 Activity 抢焦点 → 关闭残留界面 → 逐帧等待画面稳定 → 关闭透明 Activity → 交付稳定帧 → 打开文字界面。

另外：R8 资源压缩会把 manifest 里的 label 在编译期内联，所以磁贴名改成在 manifest 里直接写中文；默认资源（`values/`）也一并改为中文，避免系统语言不匹配时回退到英文。
