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

				命令语法
				------------------------------------------------------------
				  <按键> [时长ms]                按住按键, 到时间后松开 (默认 50ms)
				  <按键>+<按键>+... [时长ms]     同时按住多个按键, 例如 W+Ctrl 100
				  mouse left|right|mid [时长ms]  鼠标按键 (默认 50ms)
				  mouse move <dx> <dy>           鼠标/视角移动 (像素, +右 +下, -左 -上)
				  mouse scroll <数值>            滚轮 (正数=向上滚)
				  delay <ms> <命令>              收到请求后先等待 ms 毫秒, 再执行命令
				  release                        立即松开所有按键/鼠标

				Baritone / 聊天
				------------------------------------------------------------
				  bt <命令>                      执行 Baritone 命令, 等价于聊天框输入 #<命令>
				  #<命令>                        同上, 例如 #goal ~ ~ ~20
				  chat <文本>                    发送一条普通聊天消息 (以 / 开头则当指令发送)
				  例: bt help / bt goal ~ ~ ~20 / bt stop / bt goto 100 64 200
				  装了 Baritone 时直接调用它的 API 执行 (不经过聊天框);
				  没装 Baritone 时退化成一条普通聊天消息 (内容是 #<命令>)。

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
				  curl -X POST --data-binary 'mouse mid'        http://127.0.0.1:3420
				  curl -X POST --data-binary 'delay 80 W 50'    http://127.0.0.1:3420
				  curl -X POST --data-binary '1 50'             http://127.0.0.1:3420   # 切换到第 1 格
				  curl -X POST --data-binary 'Q 50'             http://127.0.0.1:3420   # 丢弃物品
				  curl -X POST --data-binary 'esc'              http://127.0.0.1:3420   # 暂停菜单/关闭界面
				  curl -X POST --data-binary 'F3'               http://127.0.0.1:3420   # 调试信息
				  curl -X POST --data-binary 'E 50'             http://127.0.0.1:3420   # 打开/关闭背包
				  curl -X POST --data-binary 'bt help'          http://127.0.0.1:3420   # Baritone 帮助
				  curl -X POST --data-binary 'bt goal ~ ~ ~20'  http://127.0.0.1:3420   # 走到前方 20 格
				  curl -X POST --data-binary 'bt stop'          http://127.0.0.1:3420   # 停止寻路
				  curl http://127.0.0.1:3420                                             # 本说明
				  curl http://127.0.0.1:3420/mods                                        # 已加载模组
				  curl -o shot.png http://127.0.0.1:3420/prtsc                           # 截图到文件

				  # 多行 = 顺序执行的小脚本 (上一行结束后才执行下一行)
				  curl -X POST --data-binary $'W 500\\nmouse left\\nmouse move +100 0' \\
				       http://127.0.0.1:3420

				返回
				  200  {"ok":true,"queued":<队列长度>,"inWorld":true,"actions":["W 100"]}
				  200  /prtsc 返回 PNG 图片 (Content-Type: image/png)
				  200  /mods 返回模组列表纯文本 (Content-Type: text/plain)
				  400  命令语法错误 (text/plain, 说明出错的行)
				  409  游戏客户端还没启动
				  413  请求体过大 (>64KB)

				注意事项
				时长缺省 50ms, 上限 600000ms; delay 上限为 600000ms
				同请求里的多命令将顺序执行, 命令进入单一队列
				鼠标移动会像真实鼠标一样旋转视角 (受游戏内"鼠标灵敏度"影响)
				打开界面时, 按键/鼠标事件转发给该界面 (例如 E 关闭背包, esc 返回)
				主菜单等界面一样可以操作, 例如 mouse left 点 "单人游戏" 按钮
				/prtsc 与游戏内 F2 用同一套取帧逻辑, 截的是当前帧, 不会写入 screenshots 目录
				/mods 每行 "<模组ID> <版本> <名称>", 按模组 ID 排序
				玩家信息(坐标/方位/背包)已拆到另一个模组 MC Advanced Info Fetch: GET http://127.0.0.1:3421/info
				bt/# 命令优先直接调用 Baritone API (不发聊天包), 没装 Baritone 时才走聊天
				在 shell 里用 bt 时要给整条命令加引号, 否则 bash 会把 ~ 展开成 $HOME: ./mcctl 'bt goal ~ ~ ~20'
				F3 单独按可切换调试信息; F3+其他键 (如 F3+G) 的组合暂不支持
				按键注入在主线程(渲染线程)执行, 不会抢占真实键鼠输入
				""";
	}
}
