# mcctl — Minecraft 客户端远程控制模组（Fabric / Minecraft 26.3）

把控制接口挂在 **MGHttpdProvider** 的共享 HTTP 服务（`127.0.0.1:3420`）的 `/ctl` 前缀下：

* `GET /ctl/` → 返回完整使用说明
* `POST /ctl/` → 用纯文本命令操作游戏（按键、鼠标、视角、滚轮）
* `GET /ctl/prtsc` → 截取当前游戏画面，直接返回 PNG 图片
* `GET /ctl/mouse` → 当前鼠标光标位置（窗口像素 + GUI 缩放坐标，纯文本）

`GET http://127.0.0.1:3420/` 由 MGHttpdProvider 提供，列出当前挂载的所有 endpoint（mcctl、AdvancedInfoFetcher …）。
HTTP 服务、路由和「关游戏时结束 JVM」都由 MGHttpdProvider 负责，本模组只注册自己的前缀。

命令在主线程（渲染线程）执行，走的是原版输入管线（`KeyMapping` / `Screen` 事件），
不抢占真实键鼠，也不会被反作弊当成外挂注入（这是客户端本地模组）。

```
curl -X POST --data-binary 'W 100'              http://127.0.0.1:3420/ctl
curl -X POST --data-binary 'W+Ctrl 100'         http://127.0.0.1:3420/ctl
curl -X POST --data-binary 'mouse left'         http://127.0.0.1:3420/ctl
curl -X POST --data-binary 'mouse move +30 -80' http://127.0.0.1:3420/ctl
curl -X POST --data-binary 'mouse goto 325 123' http://127.0.0.1:3420/ctl   # 光标移到像素坐标
curl -X POST --data-binary 'mouse mid'          http://127.0.0.1:3420/ctl
curl -X POST --data-binary 'delay 80 W 50'      http://127.0.0.1:3420/ctl
curl -o shot.png http://127.0.0.1:3420/ctl/prtsc    # 截图（PNG）
curl http://127.0.0.1:3420/                         # 当前可用的 endpoint（MGHttpdProvider）
curl http://127.0.0.1:3420/ctl/mouse                # 当前光标位置
```

## 命令语法

| 命令 | 说明 |
| --- | --- |
| `<按键> [时长ms]` | 按住按键，到时松开（默认 50ms） |
| `<按键>+<按键>+... [时长ms]` | 同时按住多个键，例如 `W+Ctrl 100` |
| `mouse left\|right\|mid [时长ms]` | 鼠标左/右/中键（默认 50ms） |
| `mouse move <dx> <dy>` | 相对移动鼠标/视角（像素；+右 +下，-左 -上） |
| `mouse goto <x> <y>` | 把光标移到窗口像素坐标（`GET /ctl/mouse` / 截图那套坐标；界面开着时才有意义） |
| `mouse scroll <数值>` | 滚轮（正数向上） |
| `delay <ms> <命令>` | 收到请求后先等 `ms` 毫秒再执行 |
| `release` | 立刻松开所有按键/鼠标 |
| `chat <文本>` | 发一条普通聊天消息（`/` 开头则当指令发送） |
| `type <文本>` | 往当前聚焦的文本框里逐字打字（目前主要是聊天框），文本里的 `\n` 表示回车 |
| `typeEnter` | 在文本框里按回车（发送聊天框内容） |

* 大小写不敏感，`W` = `w`，`esc` = `ESC` = `Escape`。
* 一个 POST 里可以写多行（或用 `;` 分隔），**严格按顺序执行**：下一行在上一行结束后才开始。
* 时长上限 600000ms；`delay` 上限相同。`//` 开头的行是注释。

### 打字 `type` / `typeEnter`

```bash
curl -X POST --data-binary 'T 50'         http://127.0.0.1:3420/ctl   # 先打开聊天框
curl -X POST --data-binary 'type hello'   http://127.0.0.1:3420/ctl   # 打进聊天框
curl -X POST --data-binary 'typeEnter'    http://127.0.0.1:3420/ctl   # 发送
curl -X POST --data-binary 'type hi\n'    http://127.0.0.1:3420/ctl   # 打字并回车（\n = ENTER）
curl -X POST --data-binary $'T 50\ntype hi\ntypeEnter' http://127.0.0.1:3420/ctl   # 一个请求走完
```

* 字符走原版的 `Screen#charTyped`（`CharacterEvent`），不是键码，所以不受键盘布局影响。
* 只有**聚焦的 `EditBox`** 能接收：聊天框可以；铁砧命名、告示牌、书与笔**不支持**（它们不是 `EditBox`，
  短期内也不打算支持）。创造模式背包的搜索框会被当成普通文本框接受。
* 没有可输入的文本框时返回 **400**（`type failed: no focused text box ...`）。
* 含 `type`/`typeEnter` 的请求会**同步执行完再返回**（其余请求仍是"排队后立刻返回"），
  这样失败能直接反映成 HTTP 状态。

### 聊天框开着时的按键路由

每次请求都**现读**当前界面状态，模组不缓存"聊天框是否打开"：

| 输入 | 行为 |
| --- | --- |
| `type` / `typeEnter` | 打进聊天框 |
| `E` / `Q` / `1`~`9` | **先关掉聊天框**，再执行打开背包 / 丢弃 / 切快捷栏（原版在界面开着时会跳过 `handleKeybinds()`） |
| `BACKSPACE` 方向键 `ENTER` `TAB`… | 仍然作用于聊天框（编辑/发送/补全） |
| `ESC` | 关掉聊天框 |
| `W` `A` `S` `D` `SPACE` `SHIFT`… | **不关聊天框**，直接控制游戏（可以边开着聊天框走路） |
| `mouse move` / `left` / `right` / `scroll` | 直接作用于世界（聊天框不吃鼠标） |

其余界面（背包、箱子、工作台、熔炉…）的行为不变：按键/鼠标照旧转发给该界面。

> **Baritone 支持已迁移到独立模组 [MGBaritoneConnector](https://github.com/MineAgent/baritone-connector)**：
> 它挂在同一个 3420 服务的 `/bt` 前缀下，`POST /bt/` 执行 Baritone 命令；mcctl 不再提供 `bt`/`#`。

### 支持的按键

* 字母 `A`–`Z`，数字 `0`–`9`（`1`–`9` 对应物品栏 1–9 格）
* 功能键 `F1`–`F12`（`F3` 切换调试信息）
* 修饰键 `SHIFT` `CTRL` `ALT`（也可写 `LSHIFT` `RSHIFT` `LCTRL` `RCTRL` `LALT` `RALT`）
* `SPACE` `TAB` `ENTER` `BACKSPACE` `ESC` `UP` `DOWN` `LEFT` `RIGHT`
  `PAGEUP` `PAGEDOWN` `HOME` `END` `INSERT` `DELETE` `CAPSLOCK` `NUMLOCK`
  `MINUS` `EQUAL` `COMMA` `PERIOD` `SLASH` `SEMICOLON` `APOSTROPHE`
  `LBRACKET` `RBRACKET` `BACKSLASH` `GRAVE` `KP_0`–`KP_9` `KP_ADD` 等

### 返回

| 状态码 | 含义 |
| --- | --- |
| 200 | `{"ok":true,"queued":<队列长度>,"actions":["W 100"]}` |
| 400 | 语法错误（文本里会指出第几行） |
| 409 | 游戏客户端还没启动 |
| 413 | 请求体过大（>64KB） |
| 500 | 内部错误 |

### 截图接口 `GET /ctl/prtsc`

```bash
curl -o shot.png http://127.0.0.1:3420/ctl/prtsc
```

* 返回 `200 image/png`（游戏窗口原始分辨率，不缩放），同时带 `Content-Disposition: inline`。
* 别名：`/ctl/screenshot`、`/ctl/prtsc.png`、`/ctl/screenshot.png`；`HEAD` 也可以（只回状态和类型）。
* 实现和游戏内 `F2` 用的是同一套取帧逻辑（`Screenshot.takeScreenshot` + `NativeImage`），
  但**不会**往 `screenshots/` 目录写文件——PNG 直接通过 HTTP 返回。
* 失败时：`409`（客户端没起来）、`500`（取帧超时/失败，文本里有原因）。

> **已移除 `GET /mods`**：模组列表功能删掉了。想知道装了哪些 endpoint，看 MGHttpdProvider 的
> `GET http://127.0.0.1:3420/`（它列出所有已挂载的 endpoint）。

> **玩家信息（坐标/方位/背包）在独立模组** [AdvancedInfoFetcher](https://github.com/MineAgent/AdvancedInfoFetcher)：
> 挂在同一个 3420 服务的 `/aif` 前缀下，`GET http://127.0.0.1:3420/aif/info` 返回坐标/方位/背包/副手/盔甲。

### 鼠标位置接口 `GET /ctl/mouse` 与 `mouse goto`

```bash
curl http://127.0.0.1:3420/ctl/mouse
curl -X POST --data-binary 'mouse goto 325 123' http://127.0.0.1:3420/ctl
```

`GET /ctl/mouse`（别名 `/ctl/cursor`、`/ctl/mouse.txt`）返回 `200 text/plain`，每行 `<字段>：<值>`：

```
光标：325.0 123.0      # 窗口像素坐标，和 /prtsc 截图、mouse move 的位移同一坐标系
缩放：162.5 61.5       # GUI 缩放坐标（Screen 事件用的那套）
窗口：854x480          # 窗口像素尺寸
GUI：427x240           # GUI 缩放尺寸
界面：CraftingScreen   # 当前打开的界面类名；无 = 在世界里
```

* `光标` 是 mcctl 维护的**逻辑光标**：界面开着时 `mouse goto` / `mouse move` 直接修改它，
  `mouse left/right/mid` 的事件坐标也来自它。因此 X11 和 Wayland 下读回的位置、点击命中的位置
  完全一致，不再有“不可用 / 不在窗口内”的平台分支。
* 物理指针：X11/Xwayland 下 `SDL_WarpMouseInWindow` 一定会移动它；原生 Wayland 下 SDL 通过
  `wp_pointer_warp_v1`（或 pointer confinement）请求合成器移动，**窗口有 pointer focus 时才生效**
  （GNOME/Mutter 51 已支持）。指针已经在窗口内时可以在窗口内绝对定位；指针在窗口外时 Wayland
  拿不到该 surface 的 enter serial，**无法把物理指针从窗口外拉进来**。这些情况下逻辑光标仍然工作：
  `mouse goto` 之后的 `/ctl/mouse` 和同一请求里的 `mouse left` 都按目标坐标执行。
* `mouse goto` 之后会同步 `MouseHandler#xpos/ypos` 并调用当前 `Screen#mouseMoved`，所以悬停高亮和
  紧随其后的点击都立即使用新坐标。
* **界面开着时** `mouse goto <x> <y>` 把光标放到窗口像素坐标，可以直接对着 `/ctl/prtsc` 截图量出来的
  位置点：`mouse goto 325 123` + `mouse left` 可以放在同一个请求里。指针在窗口外时也照样管用：
  绝对定位会同步逻辑坐标，点击按目标位置命中。
* **在世界里**（没有界面）窗口后端把光标收走、停在窗口中心，`mouse goto` 不生效（转视角要用
  `mouse move`），`/ctl/mouse` 报告的只是窗口中心。**这跟窗口有没有焦点无关**。
* 坐标会被夹到窗口范围内（`0..窗口-1`）。
* 一次请求里只放一个光标移动：后端把新位置回传是异步的，同一 tick 里的第二次 warp
  在 Xwayland 下会丢。`mouse goto` + `mouse left`（同一请求）没问题，两个移动叠在一起不要写。
* **手点 GUI 只是兜底手段**：界面按钮优先 `TAB`/`ENTER`，格子操作优先 Craft Command 的
  `/craft` `/furnace` `/chest` `/inventory`。`/ctl/mouse` + `mouse goto` 是在**没有 Craft Command** 时才用的。
* **长按左键破坏方块与窗口焦点无关**：窗口没有焦点时 `mouse left <时长>` 一样会持续破坏
  （`时长` 要大于该方块用当前工具的破坏时间）。

## 命令行工具

仓库里带了一个薄封装脚本 `mcctl`（内部就是 curl）：

```bash
./mcctl W 100
./mcctl mouse move +30 -80
./mcctl delay 80 W 50
./mcctl help                    # 打印完整说明
./mcctl prtsc                   # 截图，存成 mcctl-<时间戳>.png
./mcctl prtsc shot.png          # 截图到指定文件
./mcctl mousepos                # 当前光标位置 (GET /ctl/mouse)
./mcctl -f script.txt           # 每行一个请求，顺序执行
./mcctl -r 'W 20;mouse left'    # 一个请求里多条命令
MCCTL_URL=http://127.0.0.1:3420/ctl ./mcctl F3
```

## 构建

需要 JDK 25（Minecraft 26.3 要求）。Minecraft 26.1 起官方代码不再混淆，
所以 Loom 不需要任何 mappings 配置（`build.gradle` 里没有 `mappings` 行）。

```bash
./gradlew build          # 产物: build/libs/mcctl-2.0.0.jar
./gradlew runClient      # 直接启动带模组的客户端（需要正版登录/开发环境配置）
```

安装：把 `build/libs/mcctl-2.0.0.jar` **和 [MGHttpdProvider](https://github.com/MineAgent/HttpdProvider) 的 jar**（`httpdprovider-1.0.jar` 或更高，必需）一起丢进 `.minecraft/mods/`，
再装 Fabric Loader 0.20.1+（不需要 Fabric API）。启动后日志里会出现：

```
MGHttpdProvider listening on http://127.0.0.1:3420
registered /ctl (mcctl — 客户端远程控制 ...)
```

## 实现要点（Minecraft 26.3）

| 需求 | 实现 |
| --- | --- |
| 按键 | `KeyMapping.click(key)` + `KeyMapping.set(key, down)`；按键名先映射到 `key.keyboard.*`，再由 `InputConstants.getKey` 解析成 SDL scancode。转发给界面时 `KeyEvent` 需要两个码：scancode（`key()`，按键映射用）和 SDL keycode（`shortcutKey()`，`EditBox`/方向键/TAB 用），后者由 `GameKeys` 调 `SDL_GetKeyFromScancode` 取得 |
| 鼠标按键 | 同样走 `InputConstants.getKey("key.mouse.*")` + `KeyMapping` 路径（左键=攻击、右键=使用、中键=选取方块）；按钮号由 `InputConstants` 解析，不写死 0/1/2 |
| 长按左键破坏（无焦点） | 原版 `Minecraft#handleKeybinds()` 只在 `MouseHandler#isMouseGrabbed()` 为真时调用 `continueAttack`，而抓取只在窗口有焦点时才发生；`MinecraftMixin` 用 `@Redirect` 把这一处判定改成「已抓取 **或** mcctl 正按住左键」（`SyntheticInput`），所以窗口没有焦点也能持续破坏。其余原版条件（无界面、`keyAttack.isDown()`）不变 |
| 鼠标移动 | 复刻 `MouseHandler#turnPlayer` 的灵敏度公式后调用 `Entity#turn`（视角旋转同时会被同步到服务器） |
| 滚轮 | 界面打开时 `Screen#mouseScrolled`，否则反射调用 `MouseHandler#onScroll` |
| `esc` | 有界面时转发给界面（`Screen#keyPressed` 会返回上一级），否则 `Minecraft#pauseGame` |
| `F3` | `Minecraft#debugEntries.toggleDebugOverlay()`（原版这段逻辑在私有的 `KeyboardHandler#keyPress` 里） |
| 打开界面时 | 键盘/鼠标事件转发给当前 `Screen`，所以在背包里也能点格子、按 `E` 关闭 |
| `/ctl/prtsc` 截图 | `Screenshot.takeScreenshot(gameRenderer.mainRenderTarget(), image -> ...)` 取帧，`NativeImage.writeToFile` 编码成 PNG 后读回内存返回（临时文件用完即删） |
| `/ctl/mouse` | `CursorTracker` 的逻辑光标（窗口像素，从 `MouseHandler` 的合法值同步）+ `MouseHandler.getScaledXPos/YPos`（GUI 缩放）+ `Window` 尺寸 + 当前 `Screen` 类名，全部在渲染线程读 |
| `mouse goto` | 更新 `CursorTracker` → `WindowCompat` 调 `SDL_WarpMouseInWindow`（Wayland 下由合成器决定是否真正移动）→ 反射同步 `MouseHandler#xpos/ypos` → `Screen#mouseMoved`；逻辑坐标保证同一请求里紧接着的点击使用新位置 |
| Wayland resync | `MouseHandlerMixin` 在原生 Wayland 且无 pointer focus 时跳过 `resyncMousePosition`，避免 `SDL_GetGlobalMouseState` 返回 0,0 后被减去窗口位置得到负坐标 |
| 挂载 | `HttpdProvider.register("/ctl", ...)`（MGHttpdProvider 的 API，`compileOnly libs/httpdprovider-1.0.jar`）；HTTP 服务、`GET /` 索引和关游戏时的退出处理都在 provider 里 |

线程模型：HTTP 线程 → 单线程队列（保证顺序）→ `Minecraft.execute()` 到渲染线程执行输入。

## 目录

```
src/main/java/com/example/mcctl/
  McCtlClientMod.java    Fabric 客户端入口，把 /ctl 注册到 MGHttpdProvider
  ControlEndpoint.java   /ctl 前缀下的 endpoint（说明 / 命令 / 截图 / 光标）
  CommandParser.java     命令解析（纯 Java，可脱离游戏测试）
  Action.java            解析结果
  CommandRunner.java     单线程顺序执行 + 计时
  InputExecutor.java     输入抽象
  McInputExecutor.java   把动作变成真实游戏输入
  Keys.java              按键名 → 键标识（`key.keyboard.*`），保持无 Minecraft 依赖
  GameKeys.java          把键标识解析成 InputConstants 的键/按钮号
  SyntheticInput.java    mcctl 正用合成事件按住的按钮（给 mixin 用）
  WindowCompat.java      SDL 光标移动 / 鼠标焦点 / Wayland 判断（反射）
  CursorTracker.java     mcctl 的逻辑光标（窗口像素 + GUI 缩放）
  mixin/
    MinecraftMixin.java   无窗口焦点时让 handleKeybinds 继续破坏方块
    MouseHandlerMixin.java  Wayland 无焦点时跳过 MouseHandler 的 resync
  Help.java              GET /ctl/ 返回的使用说明
tools/VerifyServer.java    脱离游戏验证 HTTP + 解析层（假执行器）
tools/LoaderSmokeTest.java 用真实 26.3 运行期 classpath 验证入口点 + HTTP 服务
tools/KeyMapCheck.java     用运行期 classpath 检查所有键名/鼠标键都能被 InputConstants 解析
mcctl                      命令行封装脚本
Wayland.md                 Wayland 下 SDL 的光标行为说明与自测步骤
```

## 验证情况

**已经在真实游戏里跑通**（Minecraft 26.3 + Fabric Loader 0.20.1，
用 `--quickPlaySingleplayer test` 直接进单人存档；模组 jar 放在 `.minecraft/mods/`）。

### 实测（2026-11-17）

同一份 `mcctl-2.0.0-pre1.jar`，Minecraft 26.3 + Fabric Loader 0.20.1（Linux，游戏目录
`.minecraft/versions/26.3-Fabric`，Fabric API 0.166.2+26.3 与 8 个功能模组正常加载），`/ctl/` 注册成功，
日志里没有 `NoClassDefFoundError`、也没有窗口后端缺失告警。分别跑了 SDL 的两个视频后端：

* **原生 Wayland**（SDL 默认驱动 `wayland`）：`/ctl/mouse`、`mouse goto`、`mouse move`、点击与 X11 逐行一致；
  没有前台焦点/指针不在窗口内时物理指针不动（SDL 无法取得 enter serial），但逻辑坐标和点击仍然正确。
* **X11/Xwayland**（`SDL_VIDEODRIVER=x11`）：`mouse goto` 会真正移动物理指针（xdotool 实测窗口原点 +(目标)）。

| 测试 | 结果 |
| --- | --- |
| 启动 | 日志出现 `MGHttpdProvider listening`、`registered /ctl`；`127.0.0.1:3420` 监听；Wayland/X11 两种 driver 都通过 |
| `GET /ctl/` | 200，完整中文说明（标题为 Minecraft 26.3） |
| `GET /ctl/prtsc` | 200 `image/png`；窗口 854x480，全屏时 2160x1440 |
| 移动 | `W` / `W+CTRL` / `LSHIFT+W` / `SPACE` 均让坐标或高度变化；创造模式飞行升降也正常 |
| `mouse move` | `+300 -60` 后 `yaw 90 → 135`、`pitch 12 → 3` |
| 鼠标键 | `mouse left` 把准星下的 grass block 打掉（目标变成 dirt）；`mouse right` 放下 `oak_planks`（目标变成该方块）；`mouse mid` 正常返回 |
| `E` | 打开/关闭 `CreativeModeInventoryScreen`；搜索页签下原版会吃掉 E，属原版行为 |
| `ESC` | 进入 `PauseScreen`，再按回到世界 |
| TAB/ENTER | 暂停菜单 `TAB`×2 + `ENTER` 打开 `AdvancementsScreen`（keycode 修复生效） |
| 聊天编辑 | `T` + `type hello` + `BACKSPACE` + `LEFT` + `type X` + `typeEnter` → `<DSH> helXl`；`type abc` + `BACKSPACE` + `typeEnter` → `<DSH> ab` |
| 打字/聊天 | `type hello-263`、`typeEnter`、`chat /time set day`、`type hi\n` 全部生效 |
| 聊天路由 | 聊天框开着时 `E` 关聊天并开背包；`1` 换快捷栏；`Q` 丢弃（planks 4→3）；`W` 边打字边移动 |
| GUI 光标 | `mouse goto 100 100` 读回 `100.0 100.0` / 缩放 `50.0 50.0`；`goto 9999 9999` 夹到 `853.0 479.0`；界面内 `mouse move +50 +30` 读回 `150.0 130.0` |
| GUI 点击 | `mouse goto` + 同请求 `mouse left` 点中创造物品栏的搜索页签；用同一方式点「保存并退回到标题屏幕」和「退出游戏」走完正常退出 |
| 滚轮 | 世界里 `mouse scroll` 切换快捷栏（1→9→8→9）；GUI 里滚动创造物品列表和聊天记录（列表在顶部时正数会被夹住，用负数验证） |
| `F3` | 截图中出现调试信息，再按一次关闭 |
| `/ctl/mouse`（X11） | 暂停后 `mouse goto 100 100` 把物理指针移到窗口原点 +(100,100)，`goto 321 202` 与界面内 `+50 +30` 都按像素精确移动；分辨率 854x480，GUI 427x240 |
| `/ctl/mouse`（Wayland） | 与 X11 相同的数字输出；`mouse goto 100 100` → `100.0 100.0`，`move +50 +30` → `150.0 130.0`，`goto 9999 9999` → `853.0 479.0`，`goto 321 202` + click 打开 `AdvancementsScreen` |
| 脚本/错误 | 多行、`delay`、`release` 正常；未知键 / 坏 JSON / 空请求 400，超大请求 413 |
| 正常退出 | 从暂停菜单点「保存并退回到标题屏幕」→「退出游戏」，日志出现 `client exited, stopping the HTTP server`，退出码 `0`，端口释放，`crash-reports/` 不新增 |

本轮发现并修掉的两个问题：

1. **界面编辑键不生效**：26.3 的 `KeyEvent` 第二个分量是 SDL keycode（`shortcutKey()`），`EditBox`
   和 `Screen` 用它识别退格/Delete/方向键/Home/End/TAB；只传 scancode 会导致 `BACKSPACE`、方向键、
   TAB 导航全部失效（打字和 Enter 走 `key()`，所以看起来“部分正常”）。`GameKeys#keycode` 现在用
   `SDL_GetKeyFromScancode` 取到 keycode，`McInputExecutor` 统一构造
   `new KeyEvent(scancode, keycode, 0)`。
2. **Wayland 无焦点时的坐标污染**：`MouseHandler#resyncMousePosition` 用
   `SDL_GetGlobalMouseState` + `SDL_GetWindowPosition` 计算光标；Wayland 无 pointer focus 时
   SDL 返回 `0,0`，相减后得到 `-653,-480` 这类值。现在 `MouseHandlerMixin` 在原生 Wayland 且无焦点时
   跳过 resync；`CursorTracker` 也只采纳窗口内的值，因此 `/ctl/mouse` 始终是合法数字，且 X11/Wayland
   一致。

### 实测（2026-11-21）：窗口无焦点时的长按左键

Minecraft 26.3 + Fabric Loader 0.20.1，无头 X11（`xvfb-run` + Mesa llvmpipe；SDL3 下要设
`SDL_VIDEO_FORCE_EGL=1`），`--quickPlaySingleplayer test` 进生存存档。用
`xdotool windowfocus <root>` 让游戏窗口失焦，再 `ESC` 开/关暂停菜单把鼠标抓取丢掉
（`grabMouse()` 只在窗口有焦点时生效），此时世界里没有界面。同一流程跑改前/改后两个 jar：

| 测试 | `2.0.0-pre1`（改前） | `2.0.0`（改后） |
| --- | --- | --- |
| 无焦点、世界、生存：`mouse left 2500` 打泥土 | **不破坏**（目标方块仍是 `dirt`） | 打掉（目标方块变成下面的 `stone`） |
| 无焦点、世界、生存：给铁镐后 `mouse left 2000` 打石头 | — | 打掉（目标方块变 `未选中`） |
| 有焦点（原版抓取恢复）同样长按 | — | 打掉 |
| 创造模式 `mouse left 100`（瞬破） | — | 正常 |
| 无焦点时 GUI 点击（`mouse goto` + `mouse left` 点暂停菜单按钮） | 正常 | 正常 |
| 世界里 `mouse goto 100 100` | 不生效 | 不生效（现在与焦点无关） |

退出走暂停菜单「保存并退回到标题屏幕」→「退出游戏」：退出码 0，日志有
`client exited, stopping the HTTP server`，`crash-reports/` 不新增；日志里没有 mixin 注入错误
（`MinecraftMixin` 生效）。

其它验证：

* `./gradlew build` 干净构建通过（Loom 1.17.21 / Gradle 9.5.1 / JDK 25）。
* **解析层**：键位、鼠标、`mouse goto`、`delay`、多行脚本、`chat` 以及错误输入用例全过；
  通过 `tools/VerifyServer.java` 起真实 HTTP 服务，用 `curl` 验证 GET 说明 / POST 执行 / `GET /ctl/mouse` / 400 / JSON / 命令顺序。
* **入口点 + 服务**：`tools/LoaderSmokeTest.java` 用打包好的 jar + 真实 26.3 运行期 classpath 加载
  `McCtlClientMod`，确认 `onInitializeClient()` 正常、端口只绑 `127.0.0.1`、无游戏时 POST 与 GET /prtsc 返回 409。
* **键名解析**：`tools/KeyMapCheck.java` 在 26.3 的真实 classpath 上遍历 `Keys` 的 104 个键名和 3 个鼠标键，
  全部能由 `InputConstants.getKey` 解析（样本 `W=26 ESC=41 mouse_left=1`）。
* **`/ctl/prtsc` 传输层**：假执行器返回真 PNG，验证 `200 image/png`、magic number、别名、`HEAD`、`./mcctl prtsc 文件` 落盘。
* **游戏内 API**：所有调用都对着 `minecraft_26.3` 反编译核对过（`javap`）：
  `KeyMapping.click/set`、`MouseHandler#turnPlayer` 的灵敏度公式、`Screen#keyPressed` 的 esc 处理、
  `handleGlobalKeyPress` 的顺序、`Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)`、
  `MouseHandler#xpos/getScaledXPos/isMouseGrabbed`、`MouseHandler#releaseMouse` 里 warp
  之后直接写 `xpos/ypos` 的做法、`MouseHandler#resyncMousePosition` 的全局坐标算法、
  `AbstractContainerScreen#mouseClicked` 用事件坐标找格子、`SDL_WarpMouseInWindow`、
  26.3 的 `KeyEvent#key`（scancode，`KeyMapping` 用）与 `#shortcutKey`（keycode，`EditBox`/`Screen` 的编辑键和
  TAB/方向键导航用）、`SDL_GetKeyFromScancode`、`SDL_GetCurrentVideoDriver`、
  `InputConstants.getKey("key.keyboard.*")` 返回对应键码、`ClientPacketListener#sendChat/sendCommand`。

已知限制：

* 打字（`type` / `typeEnter`）只支持**聚焦的 `EditBox`**：聊天框可以，**铁砧命名、告示牌、书与笔不支持**
  （它们不是 `EditBox`，要逐界面特判，短期不打算做）。没有可输入的文本框时返回 400。
  创造模式物品栏的搜索框走 `isInputCaptured()` 而不是 `getFocused()`，本版的 `type` 不会往里打字。
* `type` 只认一个转义 `\n`（回车），其余反斜杠按字面处理。
* `F3` 单独按可以切换调试信息，但 `F3+X` 组合键（如 `F3+G` 区块边界）不生效——原版这段逻辑在
  私有的 `KeyboardHandler#keyPress` 里，本模组只公开复刻了 `F3` 的开关行为。
* 游戏内滚轮走反射调用 `MouseHandler#onScroll`；若将来版本改名，`mouse scroll` 会静默失效（其他命令不受影响）。
* `mouse goto` 靠反射写 `MouseHandler#xpos/ypos`；字段一旦改名，逻辑光标和点击坐标仍然正确，
  只是游戏内部的悬停高亮可能滞后。
* **长按左键破坏**：靠 `MinecraftMixin` 改写 `handleKeybinds()` 里的抓取判定；如果将来 26.x 改了
  `Minecraft#handleKeybinds` 里 `isMouseGrabbed()` 的调用形态，mixin 会在启动时报错（`defaultRequire: 1`），
  而不是静默退回“只有窗口有焦点才能破坏”。
* 同一个 tick 里先关界面、再立刻发 `mouse left`（例如 `ESC\nmouse left 2000`）时，原版 `missTime`
  会吞掉这一整段长按；分成两个请求（或中间加 `delay 50`）即可。
* **原生 Wayland 的物理指针**：`SDL_WarpMouseInWindow` 通过 `wp_pointer_warp_v1`（或 pointer
  confinement）请求合成器移动，只有在窗口有 pointer focus 且合成器支持时才生效（GNOME/Mutter 51 支持）。
  指针在窗口外时没有该 surface 的 enter serial，warp 会被合成器拒绝，**物理指针无法从窗口外进入**；
  不支持的合成器或窗口失焦时也是同样结果。但 `/ctl/mouse`、`mouse goto`、点击坐标始终与 X11 一致。
* **Wayland resync**：`MouseHandler#resyncMousePosition` 在无焦点时会把 SDL 的 `0,0` 当全局坐标；
  `MouseHandlerMixin` 在这种情况跳过它，避免 `MouseHandler#xpos/ypos` 被写成负值。
* 界面里的相对移动 `mouse move` 依赖窗口后端的光标回调；Xwayland 下 warp 不保证产生回调，所以 GUI 定位请用
  绝对坐标的 `mouse goto`，一次请求只放一个光标移动。
* **平台**：Linux 上本轮分别跑了 SDL 的原生 Wayland（逻辑光标/点击）和 X11/Xwayland 两条路径
  （Wayland 说明见 [`Wayland.md`](Wayland.md)）；原生 Wayland 的物理指针 warp 需要窗口已有 pointer
  focus，本轮未在“指针从窗口外进入”的条件下验证成功。其它平台仍未验证。
* **其它平台未测试**：Windows / macOS 都没实机跑过。Wayland 物理指针依赖合成器的
  pointer-warp 支持；Windows 的窗口/DPI 缩放、macOS 的坐标原点和 Retina 缩放都可能让
  「窗口像素」的含义需要复核。换平台后先自测一遍：
  `GET /ctl/mouse` → `mouse goto <x> <y>` → `GET /ctl/mouse` 读回坐标 → `mouse left` 是否命中。

### 退出时不再写崩溃报告

已挪到 MGHttpdProvider：共享 HTTP 服务的 `HTTP-Dispatcher` 是非 daemon 线程，关游戏时若不显式结束 JVM，
Minecraft 的 post-main 看门狗会在 15 秒后写一份 `Client shutdown from post-main` 崩溃报告。
provider 的 `ClientExitWatcher` 守候渲染线程，线程结束后停服务并 `System.exit(0)`。
细节见 [MGHttpdProvider 的 README](https://github.com/MineAgent/HttpdProvider)——本模组不再自己起服务，
也就不再持有这段逻辑。

## 构建

需要 **JDK 25**（`java -version` 与 `javac -version` 都应是 25；只有 JRE 时 Gradle 会在配置阶段报
`does not provide the required capabilities: [JAVA_COMPILER]`）。

编译依赖 MGHttpdProvider 的 API jar（`libs/httpdprovider-1.0.jar`，已在仓库里）。重新生成它：在
[HttpdProvider 仓库](https://github.com/MineAgent/HttpdProvider) 跑 `./gradlew build`，把
`build/libs/httpdprovider-1.0.jar` 复制到本仓库的 `libs/`。它是 `compileOnly`，不会被打进本模组的 jar。

```bash
./gradlew build          # -> build/libs/mcctl-<version>.jar
```

Gradle 会自动使用 `JAVA_HOME` 或 `PATH` 里的 JDK；需要指定别的 JDK 时：

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

## 安全说明

服务只绑定 `127.0.0.1`，仅本机可访问；没有鉴权（本机任何时候都能用 `curl` 控制游戏），
如果不需要请删除模组或关闭游戏。请求体上限 64KB，命令队列串行执行。

## 许可证

LGPL-3.0-only（GNU Lesser General Public License v3.0）：
完整文本见 [`LICENSE`](LICENSE)（LGPL-3.0），其中引用的 GPL-3.0 见 [`LICENSE.GPL-3.0`](LICENSE.GPL-3.0)；
所有源码文件头部标有 `SPDX-License-Identifier: LGPL-3.0-only`。
