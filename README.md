# gzhu课表

广州大学研究生课表的 Android App，纯本地运行：登录学校研究生系统查看课表，
可把整周课表放到桌面小组件上。没有服务器，账号密码只存在本机
（Android Keystore 加密）。

**下载安装**：见 [Releases](../../releases)，取最新的 `gzhu-kb-*.apk` 传到手机安装。
首次安装需在系统设置里允许"安装未知来源应用"。升级直接覆盖安装，不用卸载。

> 非官方项目，与广州大学无隶属关系，仅供个人学习使用。

## 目录

```
android/   安卓 App：构建脚本 + Java 源码 + 验证脚本
server/    Node 网页版（零依赖单文件）；它的 public/ 同时是 App 页面的唯一来源
```

两处共用 `server/public/` 下的同一份 HTML/CSS/JS——改界面只需改这一处，
构建 App 时由脚本同步进 assets。

## 想自己编译

不需要 Gradle，也不需要 Android Studio：

```bash
cd android
node build-apk.js
```

产物在 `android/out/`。需要自备 Android SDK（build-tools 34.0.0、platform 34）
与 JDK 17；JDK 位置从 `JAVA_HOME` 环境变量解析。详见
[`android/README.md`](android/README.md)，里面也记了构建与真机排障中踩到的坑。

## 开发笔记

[`AGENTS.md`](AGENTS.md) 记录了从代码里看不出来的约定：哪些改动会导致无法覆盖
安装、Android WebView 的几个硬限制、以及排查显示问题的方法。
