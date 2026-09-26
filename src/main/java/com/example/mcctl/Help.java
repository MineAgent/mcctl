// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

/**
 * The manual returned for {@code GET /}. Kept in one place so the CLI and the docs stay in sync.
 */
public final class Help {
	private Help() {
	}

	public static String text() {
		return """
				mcctl — Minecraft 客户端远程控制 (Fabric, Minecraft 26.2)
				============================================================
				监听地址: http://127.0.0.1:3420

				  GET  /            返回本使用说明
				  POST /            执行命令, 请求体为纯文本 (text/plain, UTF-8)
				  GET  /prtsc       截取当前游戏画面, 直接返回 PNG 图片 (原生分辨率)
				                    (别名: /screenshot, /prtsc.png)
				  GET  /mods        列出所有已加载模组的 ID/版本/名称 (纯文本, 每行一条)
				                    (别名: /modlist, /mods.txt)
				  GET  /mouse       当前鼠标光标位置 (纯文本, 窗口像素 + GUI 缩放坐标)
				                    (别名: /cursor, /mouse.txt)

				命令语法
				------------------------------------------------------------
				  <按键> [时长ms]                按住按键, 到时间后松开 (默认 50ms)
				  <按键>+<按键>+... [时长ms]     同时按住多个按键, 例如 W+Ctrl 100
				  mouse left|right|mid [时长ms]  鼠标按键 (默认 50ms)
				  mouse move <dx> <dy>           鼠标/视角移动 (像素, +右 +下, -左 -上)
				  mouse goto <x> <y>             把光标移到窗口像素坐标 (界面开着时才有意义)
				  mouse scroll <数值>            滚轮 (正数=向上滚)
				  delay <ms> <命令>              收到请求后先等待 ms 毫秒, 再执行命令
				  release                        立即松开所有按键/鼠标

				Baritone / 聊天
				------------------------------------------------------------
				  bt <命令>                      执行 Baritone 命令, 等价于聊天框输入 #<命令>
				  #<命令>                        同上, 例如 #goal ~ ~ ~20
				  chat <文本>                    发送一条普通聊天消息 (以 / 开头则当指令发送)
				  type <文本>                    往当前打开的文本框里逐字打字 (目前只有聊天框)
				                                 文本里的 \\n 表示回车, 例如 type hello\\n
				  typeEnter                      在文本框里按回车 (发送聊天框内容)
				  例: bt help / bt goal ~ ~ ~20 / bt stop / bt goto 100 64 200
				  装了 Baritone 时直接调用它的 API 执行 (不经过聊天框);
				  没装 Baritone 时退化成一条普通聊天消息 (内容是 #<命令>)。
				  type/typeEnter 需要当前有一个聚焦的文本框, 否则返回 400。
				  铁砧命名、告示牌、书与笔暂时不支持 (它们的文本框不是 EditBox, 且短期不打算支持)。

				聊天框开着时的按键路由 (每次请求现读界面状态, 不缓存)
				  type / typeEnter        打进聊天框
				  E / Q / 1-9             先关掉聊天框, 再执行打开背包/丢弃/切快捷栏
				  BACKSPACE 方向键 ENTER  仍然作用在聊天框 (编辑/发送/补全)
				  ESC                     关掉聊天框
				  W A S D SPACE SHIFT ... 不关聊天框, 直接控制游戏 (可以边开着聊天框走路)
				  mouse move / left / right 直接作用于世界 (聊天框不会吃掉鼠标)
				  多行脚本可写 'T 50' + 'type hello' + 'typeEnter', 会按顺序执行。

				支持的按键 (不分大小写)
				  字母   A B C ... X Y Z
				  数字   0 1 2 3 4 5 6 7 8 9      (1-9 默认绑定物品栏 1-9 格)
				  功能键 F1 F2 ... F12            (F3 = 调试信息)
				  修饰键 SHIFT  CTRL  ALT  (可写 LSHIFT/RSHIFT/LCTRL/RCTRL/LALT/RALT)
				  其他   SPACE TAB ENTER BACKSPACE ESC
				         UP DOWN LEFT RIGHT PAGEUP PAGEDOWN HOME END
				         INSERT DELETE CAPSLOCK NUMLOCK
				         MINUS EQUAL COMMA PERIOD SLASH SEMICOLON APOSTROPHE
				         LBRACKET RBRACKET BACKSLASH GRAVE
				         KP_0 ... KP_9 KP_ADD KP_SUBTRACT KP_MULTIPLY KP_DIVIDE KP_ENTER

				示例
				  curl -X POST --data-binary 'W 100'            http://127.0.0.1:3420
				  curl -X POST --data-binary 'W+Ctrl 100'       http://127.0.0.1:3420
				  curl -X POST --data-binary 'mouse left'       http://127.0.0.1:3420
				  curl -X POST --data-binary 'mouse move +30 -80' http://127.0.0.1:3420
				  curl -X POST --data-binary 'mouse goto 325 123' http://127.0.0.1:3420  # 光标移到像素坐标
				  curl -X POST --data-binary 'mouse mid'        http://127.0.0.1:3420
				  curl -X POST --data-binary 'delay 80 W 50'    http://127.0.0.1:3420
				  curl -X POST --data-binary '1 50'             http://127.0.0.1:3420   # 切换到第 1 格
				  curl -X POST --data-binary 'Q 50'             http://127.0.0.1:3420   # 丢弃物品
				  curl -X POST --data-binary 'esc'              http://127.0.0.1:3420   # 暂停菜单/关闭界面
				  curl -X POST --data-binary 'F3'               http://127.0.0.1:3420   # 调试信息
				  curl -X POST --data-binary 'E 50'             http://127.0.0.1:3420   # 打开/关闭背包
				  curl -X POST --data-binary 'T 50'             http://127.0.0.1:3420   # 打开聊天框
				  curl -X POST --data-binary 'type hello'       http://127.0.0.1:3420   # 打字进聊天框
				  curl -X POST --data-binary 'typeEnter'        http://127.0.0.1:3420   # 发送
				  curl -X POST --data-binary 'type hi\n'        http://127.0.0.1:3420   # 打字并回车
				  curl -X POST --data-binary 'bt help'          http://127.0.0.1:3420   # Baritone 帮助
				  curl -X POST --data-binary 'bt goal ~ ~ ~20'  http://127.0.0.1:3420   # 走到前方 20 格
				  curl -X POST --data-binary 'bt stop'          http://127.0.0.1:3420   # 停止寻路
				  curl http://127.0.0.1:3420                                             # 本说明
				  curl http://127.0.0.1:3420/mods                                        # 已加载模组
				  curl http://127.0.0.1:3420/mouse                                       # 当前光标位置
				  curl -o shot.png http://127.0.0.1:3420/prtsc                           # 截图到文件

				  # 多行 = 顺序执行的小脚本 (上一行结束后才执行下一行)
				  curl -X POST --data-binary $'W 500\\nmouse left\\nmouse move +100 0' \\
				       http://127.0.0.1:3420

				返回
				  200  {"ok":true,"queued":<队列长度>,"inWorld":true,"actions":["W 100"]}
				  200  /prtsc 返回 PNG 图片 (Content-Type: image/png)
				  200  /mods 返回模组列表纯文本 (Content-Type: text/plain)
				  400  命令语法错误 (text/plain, 说明出错的行)
				  400  含 type/typeEnter 的请求: 当前没有可输入的文本框 (这类请求会同步执行完再返回)
				  409  游戏客户端还没启动
				  413  请求体过大 (>64KB)

				注意事项
				时长缺省 50ms, 上限 600000ms; delay 上限为 600000ms
				同请求里的多命令将顺序执行, 命令进入单一队列
				含 type/typeEnter 的请求是同步的: 排队、执行完、再返回结果 (失败返回 400)
				鼠标移动会像真实鼠标一样旋转视角 (受游戏内"鼠标灵敏度"影响)
				打开界面时, 按键/鼠标事件转发给该界面 (例如 E 关闭背包, esc 返回);
				只有聊天框例外, 见上面的"聊天框开着时的按键路由"
				主菜单等界面一样可以操作, 例如 mouse left 点 "单人游戏" 按钮
				/prtsc 与游戏内 F2 用同一套取帧逻辑, 截的是当前帧, 不会写入 screenshots 目录
				/mods 每行 "<模组ID> <版本> <名称>", 按模组 ID 排序
				/mouse 每行 "<字段>：<值>": 光标 = 窗口像素坐标 (与截图、mouse move 的位移同一坐标系),
				       缩放 = GUI 缩放坐标, 窗口/GUI = 尺寸, 抓取 = 是表示鼠标被游戏锁住,
				       界面 = 当前界面类名
				       光标 显示 "不在窗口内，请使用 mouse goto <x> <y>" 时表示指针已经移到窗口外:
				       GLFW 只在指针位于窗口上时更新位置, 此时旧值不代表真实位置, 用绝对定位的
				       mouse goto 把它拉回窗口内再点 (跨平台判断, 用 GLFW_HOVERED, 不看具体平台)
				世界里鼠标被游戏锁住, 光标停在窗口中心, 此时位置没有意义;
				只有界面开着时 mouse goto 才生效 (它会同步游戏记录的光标位置, 同一请求里紧接着
				mouse left 也能点中; 若同步失败则先 delay 一小段再点)
				手点 GUI 只是兜底: 界面按钮优先 TAB/ENTER, 格子操作优先 /craft /furnace /chest /inventory
				玩家信息(坐标/方位/背包)已拆到另一个模组 MC Advanced Info Fetch: GET http://127.0.0.1:3421/info
				bt/# 命令优先直接调用 Baritone API (不发聊天包), 没装 Baritone 时才走聊天
				在 shell 里用 bt 时要给整条命令加引号, 否则 bash 会把 ~ 展开成 $HOME: ./mcctl 'bt goal ~ ~ ~20'
				F3 单独按可切换调试信息; F3+其他键 (如 F3+G) 的组合暂不支持
				按键注入在主线程(渲染线程)执行, 不会抢占真实键鼠输入
				""";
	}
}
