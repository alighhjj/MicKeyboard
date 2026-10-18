# MicKeyboard

基于阿里云 DashScope **Qwen3-ASR-Flash-Realtime** 模型的 Android 全局语音输入 APK，提供两种**可同时启用**的输入方式：

- **悬浮球模式**：屏幕边缘可拖动麦克风悬浮球，点击即开始/结束识别，识别文字边说边直接写入当前输入框
- **键盘模式**：系统输入法，键盘上只有一个大按钮，点击开始识别、再点一次结束并发送文本（类 Gboard 语音输入体验）

识别文字**实时逐字写入输入框**（微信、QQ、浏览器、备忘录……任何 App 均可），全程无需切换界面。应用图标为「键盘外轮廓 + 麦克风」。

本项目由已验证可通的 [qwen-asr-demo](../)（浏览器 + Node 代理版）改造为原生 Android 应用。

## 功能特性

| 功能 | 说明 |
|------|------|
| 悬浮球快速激活 | 屏幕边缘可拖动麦克风悬浮球，点击即开始/结束识别，聆听时有脉冲动画 |
| 键盘输入法模式 | 系统输入法，单按钮 toggle：开始识别 → 再点结束并发送；流式写入当前编辑框，可开关「自动发送」 |
| 文字直接输入（默认） | 增量文本**实时写入当前焦点输入框光标处**（边说边出字），无聆听窗口、无需切换 App |
| 可选聆听面板 | 设置中可开启聆听面板：浮动小窗实时显示增量文本、音量条与已确认文本 |
| 悬浮窗外观自定义 | 悬浮球 / 面板**透明度**（30%~100%）与 6 种**主题配色**实时可调，切换即时生效 |
| 快捷设置磁贴 | 通知栏下拉 → 编辑快捷设置 → 添加「MicKeyboard」，一键开关悬浮球 |
| 完整参数可调 | 模型 / 语言（zh/yue/en/ja/ko/fr/de/es/ru）/ VAD 阈值 / 静音断句时长 / 键盘自动发送 |
| 无代理直连 | Android WebSocket 可携带 `Authorization` 握手头，**不再需要 Node.js 代理** |
| 防呆保护 | 60 秒无语音自动结束会话；辅助服务未开启时自动降级复制到剪贴板 |

## 与原 Demo 的差异

浏览器 `WebSocket` 构造器无法自定义握手请求头，而 DashScope 要求 `Authorization: Bearer <key>`，原 Demo 因此需要 `proxy.js` 中转。
Android 的 OkHttp `WebSocket` 原生支持请求头，因此本 App **直连 DashScope**：

```
App (AudioRecord 16kHz PCM16)
  └─ OkHttp WebSocket ── wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime
       Header: Authorization: Bearer <DASHSCOPE_API_KEY>
```

协议与原 Demo 完全一致：`session.update` → `input_audio_buffer.append`（Base64 PCM16）→ `session.finish`；服务端事件 `conversation.item.input_audio_transcription.text`（增量）与 `.completed`（最终）。

## 使用指南

### 1. 准备

- 阿里云百炼 API Key（`sk-` 开头），并已在控制台开通 `qwen3-asr-flash-realtime` 模型
- Android 8.0（API 26）及以上

### 2. 安装并授权

1. 安装 APK，打开 App，在「识别设置」中填入 **API Key**（保存在本机 SharedPreferences）
2. 在「激活方式」中开启所需权限（两种模式可同时使用，也可任选其一）：
   - **悬浮球模式**：开启 **悬浮窗权限** + **辅助功能**（「MicKeyboard - 文本填入」）+ **通知权限**（Android 13+）
   - **键盘模式**：在「键盘模式」卡片中点击「启用输入法」，按系统提示将 MicKeyboard 添加到可用输入法列表；切换到任意输入框时点击软键盘上的地球/键盘图标，选择 MicKeyboard
3. 点击「启动悬浮图标」，屏幕边缘出现麦克风悬浮球（悬浮球模式）

### 3. 悬浮球模式（直接输入，无聆听窗口）

1. 切换到任意 App（微信/浏览器/备忘录……），**点击要输入的文本框**
2. 点击屏幕边缘的**悬浮球** → 直接开始说话，识别文字**实时逐字输入到输入框中**（悬浮球脉冲表示聆听中）
3. 停止说话（默认静音 800ms 断句）后文字已在框内；再次点击悬浮球结束会话
4. 拖动悬浮球可移动位置，松手自动吸附到屏幕边缘

### 4. 键盘模式（输入法）

1. 按上方说明启用 MicKeyboard 输入法并在输入框切换到它
2. 键盘上只有**一个麦克风大按钮**：点击开始说话，文字**实时逐字写入当前编辑框**，按钮脉冲表示聆听中
3. **再次点击按钮结束识别并发送文本**（发送行为由设置中「结束识别后自动发送」控制，默认开）；也可点右上角图标快速切换回系统键盘
4. 切换输入框时键盘自动收起初识别的残留文本，不会串输入

### 5. 悬浮窗外观

在 App「悬浮窗外观」卡片中：

- **透明度**：分别调整悬浮球 / 面板透明度（30%~100%）
- **配色**：6 种主题色（品牌蓝/翠绿/品红/橙黄/深空灰/暗夜紫），选中后悬浮球与面板即时换色，无需重启

> 辅助功能未开启时（仅键盘模式可完全脱离辅助功能工作），悬浮球模式识别结果会自动复制到剪贴板，可手动粘贴。
> 60 秒无语音会自动结束会话，避免麦克风被长期占用。

## 本地构建

```bash
# 前置：JDK 17 + Android SDK（platforms;android-35, build-tools;35.0.0）
git clone <your-repo>
cd qwen-voice-input

# 配置 SDK 路径（或设置 ANDROID_HOME 环境变量）
echo "sdk.dir=/path/to/android-sdk" > local.properties

# Debug 包
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release 包（配置 keystore.properties 后自动签名，否则产出未签名包）
./gradlew assembleRelease
```

国内网络可在 `settings.gradle.kts` 的仓库列表中加入阿里云镜像加速依赖下载。

## GitHub CI/CD 配置

仓库内置 `.github/workflows/android-ci.yml`：

- **push / PR**：自动构建 Debug + Release APK，并上传为构建产物（Artifacts）
- **打 tag（`v*`，如 `v1.0.0`）**：自动构建并创建 **GitHub Release**，附带签名后的 APK

### 启用签名发布（可选）

不配置 Secrets 也能构建，但 Release 包为未签名（`app-release-unsigned.apk`）。要发布签名包，在仓库 **Settings → Secrets and variables → Actions** 添加：

| Secret | 说明 |
|--------|------|
| `KEYSTORE_BASE64` | 签名文件的 Base64：`base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥口令 |

本地生成签名文件：

```bash
keytool -genkeypair -v -keystore release.jks -alias mickeyboard \
  -keyalg RSA -keysize 2048 -validity 10000
```

### 发布一个新版本

```bash
git tag v1.0.0
git push origin v1.0.0
```

CI 会自动编译并在 Releases 页面生成 `MicKeyboard-v1.0.0.apk`。

## 项目结构

```
qwen-voice-input/
├── .github/workflows/android-ci.yml   # CI/CD：编译、打包、发布 Release
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/qwen/voiceinput/
│       ├── MainActivity.kt                  # 设置 / 权限引导 / 识别测试 / 外观调节 / 输入法引导
│       ├── SettingsRepository.kt            # SharedPreferences 持久化（含 imeAutoSend / 外观配置）
│       ├── asr/
│       │   ├── QwenAsrClient.kt             # DashScope WebSocket 客户端（直连，无代理）
│       │   ├── PcmAudioRecorder.kt          # AudioRecord 16kHz PCM16 采集
│       │   └── AsrController.kt             # 识别会话状态机（StateFlow）
│       ├── ime/
│       │   └── VoiceInputMethodService.kt   # 键盘模式：InputMethodService 单按钮语音输入
│       └── service/
│           ├── FloatingBubbleService.kt     # 前台服务：悬浮球 + 识别面板（动态配色/透明度）
│           ├── TextInsertionAccessibilityService.kt  # 焦点输入框文本填入
│           └── VoiceInputTileService.kt     # 快捷设置磁贴
└── res/                                     # 布局 / 图标 / 字符串 / 配色
```

## 关键实现说明

- **两种输入方式**：悬浮球走 `AccessibilityService`（任意 App 通用），键盘走 `InputMethodService` 的 `InputConnection`（当前编辑框内更可靠、无需辅助功能），二者共用同一 `AsrController` 会话层
- **停止回调的线程模型（关键坑）**：`session.finished` 事件在 OkHttp reader 线程到达，`AsrController.completeStop()` 中的 `pendingResult` 回调统一 `launch(Dispatchers.Main)` 切回主线程——否则 IME/面板在回调里 `setText()`/`toast()` 会抛 `CalledFromWrongThreadException`，表现为按钮卡在「识别中」、界面不恢复
- **键盘模式的提交语义**：结束识别时仅当输入框声明了 EditorAction（发送/搜索/前往，`imeOptions & IME_MASK_ACTION`）才执行 `sendDefaultEditorAction`；纯文本输入框（备忘录/地址栏）没有发送语义，文本已在识别过程中实时写入框内
- **IME 服务主题（关键坑）**：`VoiceInputMethodService` 在 Manifest 中显式声明 `android:theme="@style/Theme.QwenVoiceInput"`——IME 服务上下文默认不是 Material 主题，布局中一旦使用 `MaterialButton` 等 Material 组件，inflate 时会触发 `ThemeEnforcement.checkMaterialTheme()` 校验失败抛 `IllegalArgumentException`，导致进程反复崩溃、键盘永远不弹出。本项目的键盘布局只使用**框架控件 + 自绘背景**（`bg_ime_voice_button`），且 `onCreateInputView()` 内置**纯代码兜底视图**，三重保证键盘在任何系统上都能正常显示
- **IME 的 method.xml 不要写 settingsActivity**：该值必须是完整组件名，而 debug 构建 applicationId 带 `.debug` 后缀，写死 `com.qwen.voiceinput.MainActivity` 会导致系统解析不到组件、整个输入法不被识别（`ime enable` 报 "Unknown input method"）
- **流式直接输入**（悬浮球模式）：`StreamTextWriter` 在会话开始时记录输入框已有文本与光标位置作为基准，每次增量到达时写入「基准前半 + 实时增量 + 基准后半」并用 `ACTION_SET_SELECTION` 保持光标位于增量末尾；每句 `completed` 确认后将基准前移、清空暂存，实现"边说边出字"且不覆盖用户已有内容。读取基准时通过 `isShowingHintText()` 过滤占位提示（hint），避免空输入框的提示文本被拼接到识别结果前
- **流式键盘写入**（键盘模式）：用 `deleteSurroundingText` 回退上一段增量再 `commitText` 写新增量，结束会话时 `sendDefaultEditorAction` 触发发送
- **一次性填入**（面板模式）：`findFocus(FOCUS_INPUT)` 定位当前焦点可编辑节点，按优先级尝试 `ACTION_PASTE`（保留光标位置）→ 游标合并 `ACTION_SET_TEXT` → 末尾追加，三级回退保证兼容性
- **动态外观**：悬浮球 / 面板背景为运行时构造的 `GradientDrawable`，颜色与透明度改动通过 `ACTION_REFRESH_STYLE` 广播即时刷新，无需重启服务
- **会话结束**：VAD 模式下严格遵循「先 `session.finish` → 等 `session.finished` → 再断开」，避免最后一句丢失
- **前台服务**：`foregroundServiceType="microphone"`（Android 14+ 要求 `FOREGROUND_SERVICE_MICROPHONE` 权限），仅聆听时占用麦克风
- **不抢焦点**：悬浮球与面板均使用 `FLAG_NOT_FOCUSABLE`，底层 App 的输入框焦点与软键盘状态不受影响
- **空闲保护**：60 秒无语音自动结束会话，防止忘记停止导致麦克风常开

## 常见问题

**识别无结果 / 连接失败？**
检查 API Key 是否正确、控制台是否开通 `qwen3-asr-flash-realtime`；面板错误区会显示服务端返回的具体原因（401/403 为鉴权失败）。

**文本没有填入输入框？**
悬浮球模式需确认已在系统设置中开启「MicKeyboard - 文本填入」辅助服务；部分自定义输入框可能只支持复制粘贴降级。键盘模式不受辅助功能开关影响。

**键盘模式没出现？**
先在 App 的「键盘模式」卡片中点击「启用输入法」完成系统引导，并在输入框的输入法切换菜单中选中 MicKeyboard。

**悬浮球点击没反应？**
检查麦克风权限是否授予；部分系统（MIUI/ColorOS）需要在应用管理中将本应用加入电池白名单，避免前台服务被清理。

**识别延迟/断句不准？**
调整「VAD 阈值」（越低越灵敏）与「静音断句时长」（越短越快、越长越完整）：语音命令场景推荐 `0.0 / 400ms`，通用听写推荐 `0.2 / 800ms`。

## 开源许可

MIT License，详见 [LICENSE](LICENSE)。
