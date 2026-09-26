# Wayland 说明（留档）

**结论：Linux 上的 Minecraft 永远跑在 X11/Xwayland 下，正常部署不会遇到原生 Wayland。**
本文件把 Wayland 相关的调查结果集中留档，README 里只保留一句结论 + 链接。

---

## 1. Minecraft 自己就把 GLFW 钉在 X11

`com.mojang.blaze3d.platform.GLX`（26.2，javap 反编译）里：

```java
if (GLFW.glfwPlatformSupported(GLFW_PLATFORM_WAYLAND)   // 0x60003
        && GLFW.glfwPlatformSupported(GLFW_PLATFORM_X11) // 0x60004
        && !SharedConstants.DEBUG_PREFER_WAYLAND) {
    GLFW.glfwInitHint(GLFW_PLATFORM /* 0x50003 */, GLFW_PLATFORM_X11);
}
GLFW.glfwInit();
```

只要 GLFW 同时编了 Wayland 和 X11 两个后端（本项目的 `lwjgl-glfw-3.4.1` natives 就是，
版本串 `3.5.0 Wayland X11 GLX Null EGL OSMesa monotonic shared`），且调试开关没开，GLFW 就强制 X11。

**在 Wayland 会话里启动 MC 也一样**：`XDG_SESSION_TYPE=wayland`、`WAYLAND_DISPLAY=wayland-0` 都在，
游戏照样走 Xwayland。实测证据：

* `/proc/<pid>/maps` 里 `wayland|xkbcommon|libdecor` 匹配数 **0**（Wayland 后端一旦起来必然映射
  `libwayland-client` 和 `libxkbcommon`）；
* 它的窗口能在 Xwayland 上枚举到：`window 0xa0000b at (574,550) size 854x480`。

## 2. 怎么强制走 Wayland（调试开关）

`DEBUG_PREFER_WAYLAND` 由 `SharedConstants.debugFlag("PREFER_WAYLAND")` 决定，它读的是
`MC_DEBUG_` 前缀的**系统属性**，并且要求总开关 `MC_DEBUG_ENABLED` 为真：

```bash
java ... -DMC_DEBUG_ENABLED=true -DMC_DEBUG_PREFER_WAYLAND=true ...
```

（`SharedConstants.booleanProperty` 对「存在但值为空」的属性也返回 true，所以 `-DMC_DEBUG_ENABLED`
不带值同样生效。）

## 3. 强制之后：MC 自己卡死，进不去游戏

实测环境：Minecraft 26.2 + Fabric Loader 0.19.5，Mesa 26.2.3，GNOME/Mutter，libdecor 0.200.5。

* 确实切到了原生 Wayland：`libwayland-client.so.0.26.0`、`libxkbcommon.so.0.13.2`、
  `libdecor-0.so.0.200.5` 都已加载，Xwayland 上不再有 MC 窗口；
* 但启动到「打开存档」时**渲染线程卡死在 `glfwSwapBuffers`**（`jcmd <pid> Thread.print`）：

```
"Render thread" RUNNABLE
  at org.lwjgl.system.JNI.invokePV(Native Method)
  at org.lwjgl.glfw.GLFW.glfwSwapBuffers(GLFW.java:2337)
  at com.mojang.blaze3d.opengl.GlSurface.present(GlSurface.java:51)
  at com.mojang.blaze3d.systems.GpuSurface.present(GpuSurface.java:98)
  at net.minecraft.client.Minecraft.renderFrame(Minecraft.java:1399)
  at net.minecraft.client.Minecraft.setScreenAndShow(Minecraft.java:2294)
  at net.minecraft.client.quickplay.QuickPlay.joinSingleplayerWorld(QuickPlay.java:89)
```

* 日志停在资源加载之后不再增长；客户端线程不再处理 `Minecraft.execute`，所以 mcctl 的 `GET /mouse`
  只会返回 `mouse position failed: ... the client thread did not answer within 5000 ms`；任何按键/鼠标命令
  同样发不进去。等 2 分钟不恢复，`crash-reports/` 也没有新文件（不是崩溃，是挂住），最后只能 `kill -9`。
* 屏幕没有锁（`org.gnome.ScreenSaver.GetActive` = `false`），所以不是息屏/遮挡节流造成的。
* 这也解释了 Mojang 为什么要默认钉 X11。

## 4. 如果将来真能在 Wayland 上跑

GLFW 源码（3.4 `src/wl_window.c`）确定的行为，以及对本模组的影响：

| 接口 | Wayland 后端 | 对 mcctl 的影响 |
| --- | --- | --- |
| `glfwSetCursorPos` | **不实现**，直接报 `GLFW_FEATURE_UNAVAILABLE`（`Wayland: The platform does not support setting the cursor position`） | `mouse goto` 的 `glfwSetCursorPos` 无效，只剩反射同步的 `MouseHandler#xpos/ypos` 生效：**点击仍能命中，物理指针不动** |
| `glfwGetCursorPos` | 返回 GLFW 自己记录的上一次位置（`window->wl.cursorPosX/Y`） | 和 X11 一样，指针离开窗口后不再更新（所以判断「在不在窗口内」不能用它） |
| `GLFW_HOVERED` | `window->wl.hovered`，由 `wl_pointer.enter/leave` 维护 | `/mouse` 的「不在窗口内，请使用 mouse goto」判断**预期可用** |
| 光标锁定 | `zwp_pointer_constraints` + `relative_pointer`，和 X11 等价 | 世界里的视角旋转（`mouse move` → `player.turn`）不受影响 |

以上都**未经实机验证**——因为第 3 节，游戏根本进不去。真要用的时候先自测一遍：

```bash
curl http://127.0.0.1:3420/mouse                          # 看 抓取 / 界面 / 光标 对不对
curl -X POST --data-binary 'mouse goto 325 123' http://127.0.0.1:3420
curl http://127.0.0.1:3420/mouse                          # 读回是不是 325.0 123.0
curl -X POST --data-binary 'mouse left' http://127.0.0.1:3420
```

## 5. 其它平台

Windows / macOS 没有实机测试。`GLFW_HOVERED`、`glfwSetCursorPos` 都是 GLFW 的跨平台接口，代码里没有
平台分支；但 Windows 的 DPI 缩放、macOS 的 Retina 坐标可能让「窗口像素」的含义需要复核。
换平台后按上面同一套自测一遍即可。
