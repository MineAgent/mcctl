# Wayland 说明

Minecraft 26.3 在 Linux 上使用 SDL 窗口后端（本实例为 SDL3 3.4.3-snapshot）。SDL 选哪个视频驱动取决于环境，
mcctl 不控制它；同一个 Linux 实例可以分别跑原生 Wayland 和 X11/Xwayland 两条路径。

## 1. 指针 warp（`mouse goto` 的物理部分）

- SDL 3 的 `SDL_WarpMouseInWindow` 在 Wayland 下会请求合成器执行 `wp_pointer_warp_v1`；
  没有该协议时退回到 pointer confinement 的 lock + position hint。
- 这两种方式都要求**窗口当前有 pointer focus**（SDL 使用 pointer enter serial）。
  GNOME/Mutter 51 已实现 `wp_pointer_warp_v1`，所以在正常前台运行、游戏窗口有焦点时，
  `mouse goto` 会真正移动物理指针。
- 关键限制：指针**已经在窗口内**时才能 warp；指针在窗口外时没有该 surface 的 enter serial，
  合成器会拒绝请求，**物理指针无法从窗口外被拉进来**。如果合成器不支持、或窗口没有焦点
  （会话不在前台、切到别的 VT），warp 同样会被忽略。
- 上述情况下 mcctl 的**逻辑光标**仍然工作：`/ctl/mouse`、`mouse goto`、点击坐标与 X11 一致，
  只是物理指针不动。
- 需要区分“会话不在前台/指针在窗口外”和“原生 Wayland 不支持 warp”：前者没有 pointer focus，
  不能作为 SDL warp 失效的证据。

## 2. 无焦点时的坐标污染

Minecraft 的 `MouseHandler#resyncMousePosition()` 用
`SDL_GetGlobalMouseState()` 加 `SDL_GetWindowPosition()` 计算光标：

- Wayland 没有真正的全局指针位置。窗口有 pointer focus 时 SDL 会用窗口位置 + 窗口内坐标伪造一个；
- 没有 pointer focus 时 `SDL_GetGlobalMouseState` 返回 `0,0`，减去窗口原点就得到
  `-653,-480` 这类负值。

`Gui.setScreen(null)` 和窗口 resize 都会调用这个方法，所以它会污染 `MouseHandler#xpos/ypos`。
`MouseHandlerMixin` 在原生 Wayland 且 `SDL_GetMouseFocus` 不是本窗口时跳过这次 resync，
保留 `grabMouse`/`releaseMouse` 设置的窗口中心或上一次真实 motion 的位置。有焦点时保留原版行为。

## 3. 逻辑光标

`CursorTracker` 维护窗口像素坐标：

- grabbed（世界里）：窗口中心；
- 界面刚打开：`releaseMouse` 已把光标放到中心，采用该值；
- 界面中：如果 `MouseHandler#xpos/ypos` 在窗口内就跟随它（物理移动仍能被 `/ctl/mouse` 看到），
  否则保留并夹取上一次逻辑值，绝不把负坐标暴露出去；
- `mouse goto` / `mouse move` 修改逻辑坐标后，同步 `MouseHandler` 并调用 `Screen#mouseMoved`，
  因此悬停高亮和同一请求里的点击立即生效。

`SDL_GetMouseState` 返回的是**窗口相对**坐标（不是全局坐标），SDL 自己也是用它维护
`MouseHandler#xpos/ypos` 的正常值；这也是为什么问题只出在 resync 的全局坐标分支。

## 4. X11/Xwayland 对照

- `SDL_VIDEODRIVER=x11`（Xwayland）：`SDL_WarpMouseInWindow` 走 `XWarpPointer`，物理指针一定移动；
- `MouseHandler#resyncMousePosition` 的全局坐标有效，mixin 不介入；
- 逻辑光标、`/ctl/mouse` 输出与原生 Wayland 完全相同的字段和语义。

## 5. 换环境后的自测

```bash
# 暂停菜单中
curl http://127.0.0.1:3420/ctl/mouse                         # 应显示数字，不是“不可用/不在窗口内”
curl -X POST --data-binary 'mouse goto 325 123' http://127.0.0.1:3420/ctl
curl http://127.0.0.1:3420/ctl/mouse                         # 读回 325.0 123.0
curl -X POST --data-binary 'mouse move +50 +30' http://127.0.0.1:3420/ctl
curl http://127.0.0.1:3420/ctl/mouse                         # 读回 375.0 153.0
curl -X POST --data-binary 'mouse left' http://127.0.0.1:3420/ctl
```

物理指针是否移动可以直接看屏幕；X11 下也可以用 `xdotool getmouselocation` 对比 warp 前后。
