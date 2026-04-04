# BetterIME - JOSM 中文输入法优化插件

解决 JOSM 编辑器中中文输入法与键盘快捷键冲突的问题。

## 问题描述

使用中文输入法时，JOSM 的快捷键（如 `S` 选择、`A` 添加节点、`W` 提高路径精度等）会被输入法拦截，导致快捷键失效。用户不得不频繁手动切换输入法，严重影响编辑效率。

## 功能

### 智能输入法切换

插件根据当前焦点组件自动控制输入法状态，分为三种情况：

**1. 自动切换至中文输入法：**
- 编辑 `name`、`name:zh`、`name:zh-Hans`、`name:zh-Hant`、`alt_name`、`operator` 标签时
- 打开 F3「搜索预设组合」对话框时

**2. 默认英文，可手动切换：**
- 其他文本输入框（如其他标签编辑等）

**3. 禁用输入法（快捷键正常工作）：**
- 地图视图、工具栏等非文本组件

### 释放 Ctrl+Space

JOSM 默认将 `Ctrl+Space` 绑定为「搜索菜单项」，与大多数系统的输入法切换快捷键冲突。本插件移除该绑定，让 `Ctrl+Space` 回归系统输入法切换功能。

## 安装

### 手动安装

1. 从 [Releases](https://github.com/nj-yzf/josm-BetterIME/releases) 页面下载 `BetterIME.jar`
2. 将 JAR 文件复制到 JOSM 插件目录：

   | 操作系统 | 路径 |
   |---------|------|
   | **Windows** | `%APPDATA%\JOSM\plugins\` |
   | **Linux** | `~/.local/share/JOSM/plugins/` |
   | **macOS** | `~/Library/JOSM/plugins/` |

   > 提示：在 JOSM 中通过 **帮助 → 显示状态报告** 可以查看你系统上的确切插件目录路径。

3. 启动（或重启）JOSM
4. 进入 **编辑 → 首选项 → 插件**，在列表中找到 **BetterIME** 并勾选启用
5. 按提示重启 JOSM

### 验证安装

重启 JOSM 后，可以通过以下方式验证插件是否生效：

- 确保中文输入法已开启
- 在地图视图上按快捷键（如 `S`、`A`），应正常触发 JOSM 功能
- 双击编辑 `name` 标签，输入法应自动切换至中文
- 编辑其他标签（如 `highway`），输入法应保持英文状态

## 构建

### 前置条件

- JDK 11 或更高版本
- JOSM tested 版本的 JAR 文件（构建脚本会自动下载）

### 使用 build.sh 构建

```bash
./build.sh
```

构建产物位于 `dist/BetterIME.jar`。

### 使用 Gradle 构建

```bash
# 构建插件
./gradlew jar

# 构建并安装到本地 JOSM 插件目录
./gradlew installPlugin

# 构建、安装并启动 JOSM 进行测试
./gradlew runJosm
```

## 兼容性

- JOSM 版本：19555 及以上
- Java 版本：11 及以上
- 操作系统：Windows

> ⚠️ 本插件仅在 Windows 系统上进行过测试。macOS 和 Linux 上的输入法框架不同，可能无法正常工作。欢迎反馈测试结果。

## 许可证

[GPL-2.0-or-later](LICENSE)（与 JOSM 一致）
