# TextGrab 中文版

在任意界面上划选文字并复制。基于 [notune/TextGrab](https://github.com/notune/TextGrab)（MIT），
针对中文、ColorOS 与日常使用场景做了大量改造。

**本仓库：** <https://github.com/Cakee007/TextGrab-zh>
**作者 GitHub：** <https://github.com/Cakee007>
**上游原版：** <https://github.com/notune/TextGrab>

---

## 怎么用

从任何界面到剪贴板，两步：

1. 下拉状态栏，点「**截屏识字**」磁贴
2. 在图上点选或拖动划选，然后复制

一次性设置：编辑快捷设置面板，把「截屏识字」拖进磁贴区。首次使用需要开启
「一步截屏」无障碍服务，磁贴才能自己截屏。

另一条路径：在相册、浏览器、聊天软件里把图片「分享」给 TextGrab，
或者从应用首页选图，同样可以划选文字。

---

## 相比上游改了什么

### 一、截屏流程（核心变化）

| 环节 | 上游做法 | 现在的做法 |
|---|---|---|
| 控制中心收回 | 无处理，截到的画面里常带下拉面板 | 透明 Activity 抢焦点，逼控制中心收起 |
| 截屏时机 | 固定延时 | 每 40 ms 取一帧，连续两帧差异 < 1 % 才认定画面稳定 |
| 上一次界面残留 | 出现「套娃」 | 序列开头发 `ACTION_DISMISS`，让旧界面自行退出 |
| 截屏能力 | 通过无障碍截屏 | 同左，但只截当前应用窗口（`takeScreenshotOfWindow`），不截整屏 |

这三个坑在 ColorOS 上尤其明显：

- **控制中心窗口收回后不会从窗口列表消失**，`mViewVisibility=0x4` 但类型仍是
  `TYPE_SYSTEM`，边界和激活状态也不变。因此窗口状态完全不可信，只能靠像素判断。
- **只有透明 Activity 能抢回焦点**，其他手段（`releaseInstance`、调整焦点标志、
  发送按键）在 ColorOS 上全部失效。
- **旧界面留在前台**会导致下一次截屏把上一次的界面截进去，形成层层嵌套。

### 二、识别精度

上游只装拉丁模型。中文场景下读汉字可以，读数字和英文很差。现在：

- **中文 + 拉丁双模型并行**，同一张图跑两遍，按行择优
- 含汉字的行走中文模型，纯数字/字母/符号的行比较两边置信度取优
- 按行重叠度（> 0.5）匹配两个模型的结果，再按索引遍历合并，
  避免重复匹配
- 长边超过 2048 时先做双线性缩放再送模型，识别完把几何坐标映射回原图。
  **不缩放的话长截图会被模型内部降采样，整行丢失**

划选逻辑保持上游的**纯视觉方案**，没有改动。

### 三、中文界面

- `values/strings.xml` 直接写成中文，不依赖系统语言匹配 `zh-rCN`
  （否则系统语言没对上时会回退到英文）
- 磁贴名称、无障碍服务名称改成中文字面量直接写进 manifest。
  R8 资源压缩会在编译期内联 label，用资源引用会变成英文

### 四、后台保活

ColorOS 会冻结闲置应用，冻结时无障碍绑定一起掉线——这就是「隔久了要重新开权限」的根因。

`KeepAliveService` 是一个 `specialUse` 类型的前台服务，在无障碍服务连上时启动，
显示一条最小重要性的静音通知：

- 没有定时器、没有 wakelock、没有网络、没有轮询
- 作用只是把进程标记为免冻结
- 实测功耗在每天 1–3 mAh 量级

### 五、退出清理

- 磁贴截的图**从不写入相册**，只在内存里。退出时释放位图，不留文件
- 在应用内打开的相册截图会登记下来，完全退出时通过 MediaStore 删除，
  Android 11+ 上进入 30 天回收站而不是直接抹掉
- 用 `isFinishing` 判断，切换应用或换图不会误删

### 六、其他

- 移除上游的分享入口与网络相关权限（识别完全离线）
- 移除仅供脚本测试的 `DebugCaptureReceiver` 及其自定义权限
- 版本号：`versionCode 5` / `1.4-zh`
- applicationId 改为 `dev.noah.textgrab.zh`，可与原版共存

---

## 构建

本地需要 JDK 17 和 Android SDK：

```bash
./gradlew assembleRelease
```

签名信息从环境变量读取（见 `app/build.gradle.kts`）：

```bash
export TG_STORE_FILE=/path/to/release.jks
export TG_STORE_PASSWORD=...
export TG_KEY_ALIAS=...
export TG_KEY_PASSWORD=...
```

### 云端构建

推送到 `main` 后由 GitHub Actions 自动构建，产物在 Actions 的 Artifacts 里。
工作流见 `.github/workflows/build.yml`。

---

## 已知限制

- **覆盖安装会清空无障碍服务列表**，系统行为，装完新版要重新开关一次
- 截屏只能拿到当前应用的窗口，悬浮窗、状态栏不在其内
- 首次授权无障碍后，磁贴才能自己截屏；未授权时只能打开已有截图

---

## 许可证

MIT，与上游一致。原始的 `LICENSE` 文件保留在本仓库中。

原项目：<https://github.com/notune/TextGrab>
