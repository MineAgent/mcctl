# mcctl — Minecraft 客户端远程控制模组（Fabric / Minecraft 26.2）

在游戏里起一个只监听 `127.0.0.1:3420` 的 HTTP 服务：

* `GET /` → 返回完整使用说明
* `POST /` → 用纯文本命令操作游戏（按键、鼠标、视角、滚轮）
* `GET /prtsc` → 截取当前游戏画面，直接返回 PNG 图片
* `GET /mods` → 列出所有已加载模组的 ID、版本、名称（纯文本）

命令在主线程（渲染线程）执行，走的是原版输入管线（`KeyMapping` / `Screen` 事件），
不抢占真实键鼠，也不会被反作弊当成外挂注入（这是客户端本地模组）。

```
curl -X POST --data-binary 'W 100'              http://127.0.0.1:3420
curl -X POST --data-binary 'W+Ctrl 100'         http://127.0.0.1:3420
curl -X POST --data-binary 'mouse left'         http://127.0.0.1:3420
curl -X POST --data-binary 'mouse move +30 -80' http://127.0.0.1:3420
curl -X POST --data-binary 'mouse mid'          http://127.0.0.1:3420
curl -X POST --data-binary 'delay 80 W 50'      http://127.0.0.1:3420
curl -X POST --data-binary 'bt goal ~ ~ ~20'    http://127.0.0.1:3420   # Baritone
curl -o shot.png http://127.0.0.1:3420/prtsc    # 截图（PNG）
curl http://127.0.0.1:3420/mods                 # 已加载模组列表
```

## 命令语法

| 命令 | 说明 |
| --- | --- |
| `<按键> [时长ms]` | 按住按键，到时松开（默认 50ms） |
| `<按键>+<按键>+... [时长ms]` | 同时按住多个键，例如 `W+Ctrl 100` |
| `mouse left\|right\|mid [时长ms]` | 鼠标左/右/中键（默认 50ms） |
| `mouse move <dx> <dy>` | 相对移动鼠标/视角（像素；+右 +下，-左 -上） |
| `mouse scroll <数值>` | 滚轮（正数向上） |
| `delay <ms> <命令>` | 收到请求后先等 `ms` 毫秒再执行 |
| `release` | 立刻松开所有按键/鼠标 |
| `bt <命令>` | 执行 Baritone 命令，等价于聊天框输入 `#<命令>` |
| `#<命令>` | 同 `bt`，例如 `#goal ~ ~ ~20` |
| `chat <文本>` | 发一条普通聊天消息（`/` 开头则当指令发送） |

* 大小写不敏感，`W` = `w`，`esc` = `ESC` = `Escape`。
* 一个 POST 里可以写多行（或用 `;` 分隔），**严格按顺序执行**：下一行在上一行结束后才开始。
* 时长上限 600000ms；`delay` 上限相同。`//` 开头的行是注释。
* 在 shell 里发 `bt` 命令要加引号，否则 bash 会把 `~` 展开成 `$HOME`：
  `./mcctl 'bt goal ~ ~ ~20'`（用 curl 时 `--data-binary '...'` 本来就是引号，没问题）。

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
mcctl 1.2.0 mcctl - Client Connect
minecraft 26.2 Minecraft
noautopause 1.0.2 noautopause
```

数据来自 Fabric Loader 的 `getAllMods()`，所以包含 Fabric API 的子模块、`minecraft`、`java` 这些内置项。

> **玩家信息（坐标/方位/背包）已拆到独立模组** [MC Advanced Info Fetch](../MC-advanced-info-fetch/)：
> 监听 `127.0.0.1:3421`，`GET /info` 返回坐标/方位/背包/副手/盔甲，和 mcctl 可以同时装。

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
./gradlew build          # 产物: build/libs/mcctl-1.2.0.jar
./gradlew runClient      # 直接启动带模组的客户端（需要正版登录/开发环境配置）
```

安装：把 `build/libs/mcctl-1.2.0.jar` 丢进 `.minecraft/mods/`，
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

线程模型：HTTP 线程 → 单线程队列（保证顺序）→ `Minecraft.execute()` 到渲染线程执行输入。

## 目录

```
src/main/java/com/example/mcctl/
  McCtlClientMod.java    Fabric 客户端入口，启动 3420 端口服务
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
```

## 验证情况

**已经在真实游戏里跑通**（Minecraft 26.2 + Fabric Loader 0.19.5 + Fabric API 0.160.0 + Baritone 1.19.0，
用 `/home/DSH/mc.sh` 启动，`--quickPlaySingleplayer test` 直接进存档；模组 jar 放在 `.minecraft/mods/`）：

| 测试 | 结果 |
| --- | --- |
| 启动 | 日志出现 `mcctl 1.2.0`，`ss -ltn` 看到 `127.0.0.1:3420` 在监听 |
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

其它验证：

* `./gradlew build` 干净构建通过（Loom 1.17.21 / Gradle 9.5.1 / JDK 25）。
* **解析层**：37 个用例全过（键位、鼠标、`delay`、多行脚本、`bt`/`#`/`chat`、以及 10 种错误输入）；
  通过 `tools/VerifyServer.java` 起真实 HTTP 服务，用 `curl` 验证 GET 说明 / POST 执行 / 400 / JSON / 命令顺序。
* **入口点 + 服务**：`tools/LoaderSmokeTest.java` 用打包好的 jar + 真实 26.2 运行期 classpath 加载
  `McCtlClientMod`，确认 `onInitializeClient()` 正常、端口只绑 `127.0.0.1`、无游戏时 POST 与 GET /prtsc 返回 409。
* **`/prtsc` 传输层**：假执行器返回真 PNG，验证 `200 image/png`、magic number、别名、`HEAD`、`./mcctl prtsc 文件` 落盘。
* **游戏内 API**：所有调用都对着 `minecraft_26.2_client.jar` 反编译核对过（`javap`）：
  `KeyMapping.click/set`、`MouseHandler#turnPlayer` 的灵敏度公式、`Screen#keyPressed` 的 esc 处理、
  `handleGlobalKeyPress` 的顺序、`Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)`、
  `ClientPacketListener#sendChat/sendCommand`，以及 Baritone 的
  `BaritoneAPI#getProvider → IBaritoneProvider#getPrimaryBaritone → IBaritone#getCommandManager → ICommandManager#execute`。

已知限制：

* `F3` 单独按可以切换调试信息，但 `F3+X` 组合键（如 `F3+G` 区块边界）不生效——原版这段逻辑在
  私有的 `KeyboardHandler#keyPress` 里，本模组只公开复刻了 `F3` 的开关行为。
* 游戏内滚轮走反射调用 `MouseHandler#onScroll`；若将来版本改名，`mouse scroll` 会静默失效（其他命令不受影响）。
* `com.sun.net.httpserver` 的 `HTTP-Dispatcher` 线程不是 daemon 线程：关游戏时主线程返回后 JVM 被它拖着，
  Minecraft 的退出看门狗会写一份 `Client shutdown from post-main` 崩溃报告（游戏本身已经存档完毕、
  进程随后被看门狗强制结束，不影响使用）。要彻底消掉这个报告需要自己实现 HTTP 循环或监听客户端退出事件。

## 构建环境说明

`gradle.properties` 里有一行 `org.gradle.java.home=/home/DSH/.jdks/temurin-25`：因为本机 `PATH`
里的 `/usr/lib/jvm/java-25-openjdk` 是 JRE（没有 `javac`），Gradle 工具链会因此报
`does not provide the required capabilities: [JAVA_COMPILER]`。换机器时把这一行删掉或改成你自己的 JDK 25 路径
（也可以 `export JAVA_HOME=<JDK25>` 后再构建）。

## 安全说明

服务只绑定 `127.0.0.1`，仅本机可访问；没有鉴权（本机任何时候都能用 `curl` 控制游戏），
如果不需要请删除模组或关闭游戏。请求体上限 64KB，命令队列串行执行。

## 许可证

LGPL-3.0-only（GNU Lesser General Public License v3.0）：
完整文本见 [`LICENSE`](LICENSE)（LGPL-3.0），其中引用的 GPL-3.0 见 [`LICENSE.GPL-3.0`](LICENSE.GPL-3.0)；
所有源码文件头部标有 `SPDX-License-Identifier: LGPL-3.0-only`。
