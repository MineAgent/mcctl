# mcctl — Minecraft 客户端远程控制模组（Fabric / Minecraft 26.2）

在游戏里起一个只监听 `127.0.0.1:3420` 的 HTTP 服务：

* `GET /` → 返回完整使用说明
* `POST /` → 用纯文本命令操作游戏（按键、鼠标、视角、滚轮）
* `GET /prtsc` → 截取当前游戏画面，直接返回 PNG 图片
* `GET /mods` → 列出所有已加载模组的 ID、版本、名称（纯文本）
* `GET /mouse` → 当前鼠标光标位置（窗口像素 + GUI 缩放坐标，纯文本）

命令在主线程（渲染线程）执行，走的是原版输入管线（`KeyMapping` / `Screen` 事件），
不抢占真实键鼠，也不会被反作弊当成外挂注入（这是客户端本地模组）。

```
curl -X POST --data-binary 'W 100'              http://127.0.0.1:3420
curl -X POST --data-binary 'W+Ctrl 100'         http://127.0.0.1:3420
curl -X POST --data-binary 'mouse left'         http://127.0.0.1:3420
curl -X POST --data-binary 'mouse move +30 -80' http://127.0.0.1:3420
curl -X POST --data-binary 'mouse goto 325 123' http://127.0.0.1:3420   # 光标移到像素坐标
curl -X POST --data-binary 'mouse mid'          http://127.0.0.1:3420
curl -X POST --data-binary 'delay 80 W 50'      http://127.0.0.1:3420
curl -X POST --data-binary 'bt goal ~ ~ ~20'    http://127.0.0.1:3420   # Baritone
curl -o shot.png http://127.0.0.1:3420/prtsc    # 截图（PNG）
curl http://127.0.0.1:3420/mods                 # 已加载模组列表
curl http://127.0.0.1:3420/mouse                # 当前光标位置
```

## 命令语法

| 命令 | 说明 |
| --- | --- |
| `<按键> [时长ms]` | 按住按键，到时松开（默认 50ms） |
| `<按键>+<按键>+... [时长ms]` | 同时按住多个键，例如 `W+Ctrl 100` |
| `mouse left\|right\|mid [时长ms]` | 鼠标左/右/中键（默认 50ms） |
| `mouse move <dx> <dy>` | 相对移动鼠标/视角（像素；+右 +下，-左 -上） |
| `mouse goto <x> <y>` | 把光标移到窗口像素坐标（`GET /mouse` / 截图那套坐标；界面开着时才有意义） |
| `mouse scroll <数值>` | 滚轮（正数向上） |
| `delay <ms> <命令>` | 收到请求后先等 `ms` 毫秒再执行 |
| `release` | 立刻松开所有按键/鼠标 |
| `bt <命令>` | 执行 Baritone 命令，等价于聊天框输入 `#<命令>` |
| `#<命令>` | 同 `bt`，例如 `#goal ~ ~ ~20` |
| `chat <文本>` | 发一条普通聊天消息（`/` 开头则当指令发送） |
| `type <文本>` | 往当前聚焦的文本框里逐字打字（目前主要是聊天框），文本里的 `\n` 表示回车 |
| `typeEnter` | 在文本框里按回车（发送聊天框内容） |

* 大小写不敏感，`W` = `w`，`esc` = `ESC` = `Escape`。
* 一个 POST 里可以写多行（或用 `;` 分隔），**严格按顺序执行**：下一行在上一行结束后才开始。
* 时长上限 600000ms；`delay` 上限相同。`//` 开头的行是注释。
* 在 shell 里发 `bt` 命令要加引号，否则 bash 会把 `~` 展开成 `$HOME`：
  `./mcctl 'bt goal ~ ~ ~20'`（用 curl 时 `--data-binary '...'` 本来就是引号，没问题）。

### 打字 `type` / `typeEnter`

```bash
curl -X POST --data-binary 'T 50'         http://127.0.0.1:3420   # 先打开聊天框
curl -X POST --data-binary 'type hello'   http://127.0.0.1:3420   # 打进聊天框
curl -X POST --data-binary 'typeEnter'    http://127.0.0.1:3420   # 发送
curl -X POST --data-binary 'type hi\n'    http://127.0.0.1:3420   # 打字并回车（\n = ENTER）
curl -X POST --data-binary $'T 50\ntype hi\ntypeEnter' http://127.0.0.1:3420   # 一个请求走完
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

### Baritone 支持

```bash
curl -X POST --data-binary 'bt help'          http://127.0.0.1:3420
curl -X POST --data-binary 'bt goal ~ ~ ~20'  http://127.0.0.1:3420
curl -X POST --data-binary 'bt stop'          http://127.0.0.1:3420
./mcctl 'bt goto 100 64 200'
```

装了 Baritone（mod id `baritone`）时，`bt`/`#` 命令**直接调用 Baritone 的 API**：

```
baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone()
        .getCommandManager().execute("goal ~ ~ ~20")
```

也就是聊天框里 `#goal ~ ~ ~20` 内部走的同一条路，区别是不用真的发一个聊天包出去。
没装 Baritone（或 API 调用失败）时，自动退化成发送聊天消息 `#<命令>`，由 Baritone 的聊天钩子处理
（这种情况会和普通聊天一样出现在服务器日志里）。

Baritone 的类型全部用反射调用，所以本模组**不依赖** Baritone：装不装都能用。

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

### 截图接口 `GET /prtsc`

```bash
curl -o shot.png http://127.0.0.1:3420/prtsc
```

* 返回 `200 image/png`（游戏窗口原始分辨率，不缩放），同时带 `Content-Disposition: inline`。
* 别名：`/screenshot`、`/prtsc.png`、`/screenshot.png`；`HEAD` 也可以（只回状态和类型）。
* 实现和游戏内 `F2` 用的是同一套取帧逻辑（`Screenshot.takeScreenshot` + `NativeImage`），
  但**不会**往 `screenshots/` 目录写文件——PNG 直接通过 HTTP 返回。
* 失败时：`409`（客户端没起来）、`500`（取帧超时/失败，文本里有原因）。

### 模组列表接口 `GET /mods`

```bash
curl http://127.0.0.1:3420/mods
./mcctl mods
```

返回 `200 text/plain; charset=utf-8`，每行一个模组，格式 `<模组ID> <版本> <名称>`，按模组 ID 排序
（别名 `/modlist`、`/mods.txt`）：

```
advanced-info-fetch 1.0.0 MC Advanced Info Fetch
baritone 1.19.0 Baritone
craftcmd 1.1.0 Craft Command
fabric-api 0.160.0+26.2 Fabric API
fabricloader 0.19.5 Fabric Loader
java 25 OpenJDK 64-Bit Server VM
mcctl 1.6.0 mcctl - Client Connect
minecraft 26.2 Minecraft
noautopause 1.0.2 noautopause
```

数据来自 Fabric Loader 的 `getAllMods()`，所以包含 Fabric API 的子模块、`minecraft`、`java` 这些内置项。

> **玩家信息（坐标/方位/背包）已拆到独立模组** [AdvancedInfoFetcher](https://github.com/MineAgent/AdvancedInfoFetcher)：
> 监听 `127.0.0.1:3421`，`GET /info` 返回坐标/方位/背包/副手/盔甲，和 mcctl 可以同时装。

### 鼠标位置接口 `GET /mouse` 与 `mouse goto`

```bash
curl http://127.0.0.1:3420/mouse
curl -X POST --data-binary 'mouse goto 325 123' http://127.0.0.1:3420
```

`GET /mouse`（别名 `/cursor`、`/mouse.txt`）返回 `200 text/plain`，每行 `<字段>：<值>`：

```
光标：325.0 123.0      # 窗口像素坐标，和 /prtsc 截图、mouse move 的位移同一坐标系
缩放：162.5 61.5       # GUI 缩放坐标（Screen 事件用的那套）
窗口：854x480          # 窗口像素尺寸
GUI：427x240           # GUI 缩放尺寸
抓取：否               # 是 = 鼠标被游戏锁住（在世界里），此时光标停在窗口中心，位置没有意义
界面：CraftingScreen   # 当前打开的界面类名；无 = 在世界里
```

指针被移到窗口外时，`光标` / `缩放` 两行会换成：

```
光标：不在窗口内，请使用 mouse goto <x> <y>
```

* 为什么需要这一行：GLFW **只在指针位于窗口内容区上方时**才投递移动事件，所以指针一旦离开窗口,
  `MouseHandler#xpos/ypos` 就冻结在最后一个窗口内像素上——看起来像个合法坐标，其实已经不作数了
  （实测：窗口在 (653,515)/854x480，把指针扔到 (200,200) 或 (2100,1300)，`/mouse` 都还报 `427,240`）。
* 判断用的是 `GLFW.glfwGetWindowAttrib(window, GLFW_HOVERED)`，语义就是「光标是否在窗口内容区上方」，
  **X11 / Wayland / Windows / macOS 各后端都由 GLFW 统一实现，代码里没有任何平台分支**
  （Linux 上的 MC 固定走 X11/Xwayland，实测也只做过这一条；详见下面的「已知限制」）。
  不用 `glfwGetCursorPos` 是因为它有平台差异：X11 会返回负数/超界坐标，Wayland 客户端根本拿不到全局指针位置。
* **界面开着时**（`抓取：否`）`mouse goto <x> <y>` 把光标放到窗口像素坐标，可以直接对着 `/prtsc` 截图量出来的
  位置点：`mouse goto 325 123` + `mouse left` 可以放在同一个请求里。指针在窗口外时也照样管用——
  绝对定位是自纠正的，会把指针一起拉回窗口内（实测从窗口外 `goto 321 202`，物理指针回到 `(974,717)` =
  窗口原点 `(653,515)` + 目标，点击命中）。
* **在世界里**（`抓取：是`）GLFW 把光标禁用了，`mouse goto` 不生效（转视角要用 `mouse move`），
  `/mouse` 报告的只是窗口中心。
* 坐标会被夹到窗口范围内（`0..窗口-1`）。
* 一次请求里只放一个光标移动：GLFW 把新位置回传是异步的，同一 tick 里的第二次 `glfwSetCursorPos`
  在 Xwayland 下会丢。`mouse goto` + `mouse left`（同一请求）没问题，两个移动叠在一起不要写。
* **手点 GUI 只是兜底手段**：界面按钮优先 `TAB`/`ENTER`，格子操作优先 Craft Command 的
  `/craft` `/furnace` `/chest` `/inventory`。`/mouse` + `mouse goto` 是在**没有 Craft Command** 时才用的。

## 命令行工具

仓库里带了一个薄封装脚本 `mcctl`（内部就是 curl）：

```bash
./mcctl W 100
./mcctl mouse move +30 -80
./mcctl delay 80 W 50
./mcctl help                    # 打印完整说明
./mcctl prtsc                   # 截图，存成 mcctl-<时间戳>.png
./mcctl prtsc shot.png          # 截图到指定文件
./mcctl mods                    # 已加载模组 ID/版本/名称 (GET /mods)
./mcctl -f script.txt           # 每行一个请求，顺序执行
./mcctl -r 'W 20;mouse left'    # 一个请求里多条命令
MCCTL_URL=http://127.0.0.1:3420 ./mcctl F3
```

## 构建

需要 JDK 25（Minecraft 26.2 要求）。Minecraft 26.1 起官方代码不再混淆，
所以 Loom 不需要任何 mappings 配置（`build.gradle` 里没有 `mappings` 行）。

```bash
./gradlew build          # 产物: build/libs/mcctl-1.6.0.jar
./gradlew runClient      # 直接启动带模组的客户端（需要正版登录/开发环境配置）
```

安装：把 `build/libs/mcctl-1.6.0.jar` 丢进 `.minecraft/mods/`，
再装 Fabric Loader 0.19.5+（不需要 Fabric API）。启动后日志里会出现：

```
mcctl listening on http://127.0.0.1:3420
```

## 实现要点（Minecraft 26.2）

| 需求 | 实现 |
| --- | --- |
| 按键 | `KeyMapping.click(key)` + `KeyMapping.set(key, down)`，按 GLFW 键码投递（`InputConstants.Type.KEYSYM`） |
| 鼠标按键 | `InputConstants.Type.MOUSE` + 同样的 `KeyMapping` 路径（左键=攻击、右键=使用、中键=选取方块） |
| 鼠标移动 | 复刻 `MouseHandler#turnPlayer` 的灵敏度公式后调用 `Entity#turn`（视角旋转同时会被同步到服务器） |
| 滚轮 | 界面打开时 `Screen#mouseScrolled`，否则反射调用 `MouseHandler#onScroll` |
| `esc` | 有界面时转发给界面（`Screen#keyPressed` 会返回上一级），否则 `Minecraft#pauseGame` |
| `F3` | `Minecraft#debugEntries.toggleDebugOverlay()`（原版这段逻辑在私有的 `KeyboardHandler#keyPress` 里） |
| 打开界面时 | 键盘/鼠标事件转发给当前 `Screen`，所以在背包里也能点格子、按 `E` 关闭 |
| `/prtsc` 截图 | `Screenshot.takeScreenshot(gameRenderer.mainRenderTarget(), image -> ...)` 取帧，`NativeImage.writeToFile` 编码成 PNG 后读回内存返回（临时文件用完即删） |
| `/mods` | `FabricLoader#getAllMods()` → `ModMetadata#getId/getVersion/getName`，按 ID 排序，输出 `<id> <version> <name>` |
| `/mouse` | `MouseHandler#xpos/ypos`（窗口像素）+ `getScaledXPos/YPos`（GUI 缩放）+ `isMouseGrabbed` + `Window` 尺寸 + 当前 `Screen` 类名，全部在渲染线程读 |
| `mouse goto` | `GLFW.glfwSetCursorPos(窗口像素)`；随后反射同步 `MouseHandler#xpos/ypos`（复刻 `MouseHandler#releaseMouse` 的做法），否则同一请求里紧接着的点击会用旧坐标 |
| 关游戏 | `ClientExitWatcher` 守候渲染线程（`Minecraft#getRunningThread()`）；线程结束后停掉本模组的 HTTP 服务并 `System.exit(0)`，赶在 post-main 看门狗写报告之前结束 JVM |

线程模型：HTTP 线程 → 单线程队列（保证顺序）→ `Minecraft.execute()` 到渲染线程执行输入。

## 目录

```
src/main/java/com/example/mcctl/
  McCtlClientMod.java    Fabric 客户端入口，启动 3420 端口服务
  ClientExitWatcher.java 守候渲染线程，客户端退出后停掉服务（消除 post-main 崩溃报告）
  ControlServer.java     HTTP 服务（JDK 自带 com.sun.net.httpserver）
  CommandParser.java     命令解析（纯 Java，可脱离游戏测试）
  Action.java            解析结果
  CommandRunner.java     单线程顺序执行 + 计时
  InputExecutor.java     输入抽象
  McInputExecutor.java   把动作变成真实游戏输入
  Keys.java              按键名 → GLFW 键码
  Help.java              GET / 返回的使用说明
tools/VerifyServer.java    脱离游戏验证 HTTP + 解析层（假执行器）
tools/LoaderSmokeTest.java 用真实 26.2 运行期 classpath 验证入口点 + HTTP 服务
mcctl                      命令行封装脚本
Wayland.md                 Wayland 相关的调查留档（平台结论见「已知限制」）
```

## 验证情况

**已经在真实游戏里跑通**（Minecraft 26.2 + Fabric Loader 0.19.5 + Fabric API 0.160.0 + Baritone 1.19.0，
用 `Documents/spMC/TestSave.sh` 启动，`--quickPlaySingleplayer test` 直接进存档；模组 jar 放在 `.minecraft/mods/`）：

| 测试 | 结果 |
| --- | --- |
| 启动 | 日志出现 `mcctl 1.6.0`，`ss -ltn` 看到 `127.0.0.1:3420` 在监听 |
| `GET /` | 200，返回完整中文说明 |
| `GET /prtsc` | 200 `image/png`，854x480（窗口原生分辨率）、306KB、0.14s；画面就是存档里的丛林场景 |
| `W 1500` | 截图对比：玩家确实往前走了一段 |
| `mouse move +300 -60` | 截图对比：视角明显抬起/右转 |
| `W+CTRL 800` | 正常返回，键位组合生效 |
| `bt help` | Baritone 输出完整命令列表（`[Baritone] All Baritone commands...`） |
| `bt goal ~ ~ ~20` | `[Baritone] Goal: GoalBlock{x=-219,y=105,z=113}` |
| `bt stop` | `[Baritone] ok canceled` |
| `bt proc` / `bt help goal` | `No process in control` / goal 子命令帮助 |
| `chat hello from mcctl` | 聊天里出现 `<DSH> hello from mcctl` |
| `GET /mods` | `200 text/plain`；列出 57 个已加载模组（含 Fabric API 子模块、`minecraft`、`java`），格式 `<id> <version> <name>` 按 ID 排序 |
| `/mods` 别名 | `/modlist`、`/mods.txt` 都返回同样的列表；`./mcctl mods` 输出一致 |
| `delay 200 mouse move 0 +120` + 多行脚本 | 按顺序执行，JSON 回显规范化后的命令 |
| `GET /mouse`（世界里） | `光标：427.0 240.0`、`抓取：是`、`界面：无`（854x480，GUI 427x240） |
| `GET /mouse`（暂停菜单） | `ESC` 后 `抓取：否`、`界面：PauseScreen`，光标回到窗口中心 |
| `mouse goto 100 100` | 读回 `光标：100.0 100.0`、`缩放：50.0 50.0` |
| `mouse goto 321 202` + `mouse left`（同一请求） | 点中暂停菜单的「进度」按钮，`界面：AdvancementsScreen` |
| `mouse goto 427 154` + `mouse left` | 点中「回到游戏」，回到世界（`抓取：是`、`界面：无`）；分成两个请求也成立 |
| `mouse goto 9999 9999` | 夹到窗口边界：`光标：853.0 479.0` |
| 光标被移到窗口外 | 窗口在 (653,515)/854x480，指针扔到 (200,200) 或 (2100,1300) 后 `/mouse` 输出 `光标：不在窗口内，请使用 mouse goto <x> <y>`；此时 `mouse left` 用旧值点不中，`mouse goto 321 202` + `mouse left` 命中（`界面：AdvancementsScreen`），物理指针回到 (974,717) |
| `mouse move +50 +30`（界面里，已知起点） | 从 300,300 移到 350,330，读回稳定 |
| 正常退出（旧行为） | 关窗口后 15s 必现 `Client shutdown from post-main` 崩溃报告（post-main 看门狗），退出码 `-8` |
| 正常退出（本版） | 关窗口后日志依次出现 `client exited, stopping the mcctl server`、`exiting the JVM so the post-main shutdown watchdog cannot fire`；进程退出码 `0`，`crash-reports/` 不新增文件 |

其它验证：

* `./gradlew build` 干净构建通过（Loom 1.17.21 / Gradle 9.5.1 / JDK 25）。
* **解析层**：41 个用例全过（键位、鼠标、`mouse goto`、`delay`、多行脚本、`bt`/`#`/`chat`、以及 12 种错误输入）；
  通过 `tools/VerifyServer.java` 起真实 HTTP 服务，用 `curl` 验证 GET 说明 / POST 执行 / `GET /mouse` / 400 / JSON / 命令顺序。
* **入口点 + 服务**：`tools/LoaderSmokeTest.java` 用打包好的 jar + 真实 26.2 运行期 classpath 加载
  `McCtlClientMod`，确认 `onInitializeClient()` 正常、端口只绑 `127.0.0.1`、无游戏时 POST 与 GET /prtsc 返回 409。
* **`/prtsc` 传输层**：假执行器返回真 PNG，验证 `200 image/png`、magic number、别名、`HEAD`、`./mcctl prtsc 文件` 落盘。
* **游戏内 API**：所有调用都对着 `minecraft_26.2_client.jar` 反编译核对过（`javap`）：
  `KeyMapping.click/set`、`MouseHandler#turnPlayer` 的灵敏度公式、`Screen#keyPressed` 的 esc 处理、
  `handleGlobalKeyPress` 的顺序、`Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)`、
  `MouseHandler#xpos/getScaledXPos/isMouseGrabbed`、`MouseHandler#releaseMouse` 里 `glfwSetCursorPos`
  之后直接写 `xpos/ypos` 的做法、`AbstractContainerScreen#mouseClicked` 用事件坐标找格子，
  `GLFW#glfwGetWindowAttrib(GLFW_HOVERED)` 判断指针是否在窗口内容区上方（跨平台），
  `ClientPacketListener#sendChat/sendCommand`，以及 Baritone 的
  `BaritoneAPI#getProvider → IBaritoneProvider#getPrimaryBaritone → IBaritone#getCommandManager → ICommandManager#execute`。

已知限制：

* 打字（`type` / `typeEnter`）只支持**聚焦的 `EditBox`**：聊天框可以，**铁砧命名、告示牌、书与笔不支持**
  （它们在 26.2 里不是 `EditBox`，要逐界面特判，短期不打算做）。没有可输入的文本框时返回 400。
* `type` 只认一个转义 `\n`（回车），其余反斜杠按字面处理。
* `F3` 单独按可以切换调试信息，但 `F3+X` 组合键（如 `F3+G` 区块边界）不生效——原版这段逻辑在
  私有的 `KeyboardHandler#keyPress` 里，本模组只公开复刻了 `F3` 的开关行为。
* 游戏内滚轮走反射调用 `MouseHandler#onScroll`；若将来版本改名，`mouse scroll` 会静默失效（其他命令不受影响）。
* `mouse goto` 靠反射写 `MouseHandler#xpos/ypos`，字段一旦改名就只剩 `glfwSetCursorPos` 本身的效果
  （真 X11 下光标照样会动，只是同一请求里的点击可能用到旧坐标）；`/mouse` 读的是公开的 `xpos()`，不受影响。
* 界面里的相对移动 `mouse move` 依赖 GLFW 的光标回调；Xwayland 下 warp 不保证产生回调，所以 GUI 定位请用
  绝对坐标的 `mouse goto`，一次请求只放一个光标移动。
* **平台：Linux 上的 Minecraft 永远跑在 X11/Xwayland 下**，所以 `/mouse`、`mouse goto` 实际只有这一条
  路径。26.2 在 `GLX` 里写死 `glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)`：只要 Wayland 和 X11
  两个后端都编进来了、且 `DEBUG_PREFER_WAYLAND` 为假，就强制 X11——会话是 Wayland 也一样，游戏走
  Xwayland。**Wayland 相关的调查（怎么强制、为什么跑不起来、GLFW 各后端的差异）全部收在
  [`Wayland.md`](Wayland.md)**，这里只留结论。
* **其它平台未测试**：Windows / macOS 都没实机跑过。`GLFW_HOVERED`（指针是否在窗口内容区上方）
  由 GLFW 各后端统一实现，预期一致；Windows 的窗口/DPI 缩放、macOS 的坐标原点和 Retina 缩放都可能让
  「窗口像素」的含义需要复核。换平台后先自测一遍：
  `GET /mouse` → `mouse goto <x> <y>` → `GET /mouse` 读回坐标 → `mouse left` 是否命中。

### 退出时不再写崩溃报告

关游戏时渲染线程返回后，`Main` 会启动一个 post-main 看门狗：15 秒内 JVM 还没结束，它就写一份
`Client shutdown from post-main` 崩溃报告，然后 `System.exit(-8)`。而 JVM 只有**所有非 daemon 线程**都结束后
才会自己退出——`com.sun.net.httpserver` 每个服务都带一个非 daemon 的 `HTTP-Dispatcher` 线程（本模组一个，
AdvancedInfoFetcher 之类的模组还会再有一个），Baritone 也留着非 daemon 的 worker pool。
JVM 关闭钩子救不了这个场景：JVM 根本没开始关闭，钩子不会执行。

`ClientExitWatcher` 在渲染线程（`Minecraft#getRunningThread()`）上 `join()`，线程结束后先停掉本模组的 HTTP 服务
（`HttpServer#stop(0)`），再显式 `System.exit(0)`。关闭钩子照常执行（Minecraft 自己的那个也在内），
所以看门狗永远不会触发；这时世界早已保存、窗口早已关闭（`exitWorldAndClose()` 在 `main()` 返回前就跑完了），
强制退出不会丢存档。既不依赖 Fabric API 的生命周期事件（本模组依旧只依赖 Fabric Loader），
也不用自己实现 HTTP 循环。

## 构建

需要 **JDK 25**（`java -version` 与 `javac -version` 都应是 25；只有 JRE 时 Gradle 会在配置阶段报
`does not provide the required capabilities: [JAVA_COMPILER]`）。

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
