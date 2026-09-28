# gzhu课表

广州大学研究生课表安卓 App，纯本地运行：登录学校研究生系统查看课表，
可把整周课表放到桌面小组件上。没有服务器，账号密码只存在本机（Android
Keystore 加密）。

<p>
<img alt="minSdk" src="https://img.shields.io/badge/minSdk-21%20(Android%205.0)-3ddc84">
<img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-34-3ddc84">
<img alt="size" src="https://img.shields.io/badge/APK-73%20KB-blue">
<img alt="deps" src="https://img.shields.io/badge/third--party%20deps-0-brightgreen">
<img alt="gradle" src="https://img.shields.io/badge/build-no%20Gradle%20needed-orange">
</p>

---

## 下载安装

去 [**Releases**](../../releases) 下载最新的 `gzhu-kb-<版本>-build<构建号>.apk`，
传到手机点安装即可（首次需在系统设置里允许"安装未知来源应用"）。

> APK **不在仓库文件里**，只作为 Release 附件提供——二进制产物提交进 git 会让
> 仓库随每次发版持续膨胀。升级直接覆盖安装，**不用卸载**，已保存的密码不会丢。

想自己编译看 [第四节](#四自己重新构建)，不需要 Gradle 也不需要 Android Studio。

## 功能

| | |
| --- | --- |
| 体积 | 73 KB。没有引 AndroidX 或任何第三方库 |
| 数据 | 不经过任何服务器，账号密码只存在本机，Keystore 加密 |
| 桌面小组件 | 整周课表缩略图，当前周高亮"今天"，定时刷新 |
| 免登录 | 记住密码后打开 App 直接进课表 |
| 左栏时间 | 每节课显示节次与上课/下课时间 |
| 构建 | 一条 `node build-apk.js` 出 APK，不需要 Gradle / Android Studio |

> 非官方项目，与广州大学无隶属关系，仅供个人学习使用。
> 图标是脚本生成的，未使用学校校徽。

### 装完怎么更新

**把课表划到底**，会看到仓库地址和构建号：

```
github.com/cc-pro-20/gzhu-grad-schedule
构建 1.1.18（build18）
```

点仓库地址会用系统浏览器打开（App 内不打开，避免和我们拦截请求的机制打架），
去 Releases 拿最新 APK 覆盖安装即可——**升级不用卸载**，已保存的密码也不会丢。
报问题时把"构建 x.y.z（buildN）"这一行发出来，就能确认你装的是哪一次构建。

> 这两行早先是固定在屏幕底部的，一直占着视线。现在随页面滚动、
> 排在课表下方，不划到底不会出现。

---

## 一、它是怎么在手机上跑通的

有三个设计决定值得说明。

### 1. 用 shouldInterceptRequest 接管页面请求

课表页面里的 `fetch('/api/...')` 需要**同源**才能工作，而学校接口没有开
CORS 头，直接从前端调是拿不到数据的。

解决方式是重写 `WebViewClient.shouldInterceptRequest`，把页面发出的**所有**请求
拦下来自己响应：

| 请求 | 处理方式 |
| --- | --- |
| `/style.css`、`/app.js` 等 | 从 `assets/www` 读取，包成 `WebResourceResponse` |
| `/api/*` | 交给原生 `KbRepository`（内部用 `Http`/`School` 直接请求学校系统） |
| 其它 | 404 |

返回非 null 就意味着 WebView **不会发起真实网络请求，也不会做 CORS 检查**，
所以在页面看来这些接口就是同源的本站接口 —— CORS 彻底不参与，
而且不需要端口、不需要 socket、不需要维护服务端生命周期。
`shouldInterceptRequest` 本身就在后台线程回调，联网可以直接在里面做。

页面仍然原封不动地复用 `server/public/` 那份 HTML/CSS/JS。

**一个必须交代的取舍**：`WebResourceRequest` 拿不到 POST 请求体，
所以页面自己提交账号密码那条路走不通。处理方式是：

- `POST /api/login` 一律返回 `401 {"native":true}`；
- 页面里的 `doLogin()` 若发现 `window.AndroidBridge` 存在，就把凭据交给
  原生登录界面（`Bridge.nativeLogin`）完成登录。

即：**安卓端登录由原生界面负责**，其余（周次切换、课表网格、课程详情）全部复用同一份网页代码。

### 1.5 aapt2 会把 assets 条目名写成反斜杠（会导致页面 404）

这是个只在 Windows 上出现、而且**编译期完全看不出来**的坑：

```
aapt2 link -A app/assets ...
  -> APK 里的原始条目名是  assets/www\index.html   （注意 www\ 是名字的一部分）
  -> Android 的 AssetManager 只认正斜杠
  -> 运行时 getAssets().open("www/index.html") 抛 IOException
  -> 页面表现为 "404 Not Found: index.html"
```

实测 `-A` 写成 `app/assets`、`./app/assets`、`app\assets` 三种都会产出反斜杠，
所以只能在打包后修：构建脚本的第 4.5 步用 `fix-apk-assets.js` 解压 APK、
按**中央目录里的原始条目名**重建层级（磁盘上的文件名已被 jar 修正过，
不能作为判断依据）、再重新打包。

同时加了 `check-apk.js` 作为构建门禁：一旦 APK 里再出现反斜杠条目、
关键文件缺失、或混入我们自己的临时文件，构建立刻失败，不会等到装到手机上才发现。

> 另外注意：`java -Dfile.encoding` 那类 JDK 参数、以及所有 Android SDK 工具
> 都不能吃含中文的**绝对路径**（只要路径里有非 ASCII 字符就会失败）。
> 构建脚本因此统一用「cwd 设为项目目录 + 参数只用相对路径」的写法。

### 1.6 resources.arsc 被压缩会导致安装失败（"兼容性问题"）

比 1.5 更严重的一个坑：**安装器直接拒装**，报"出现兼容性问题"。

Android 11+ 要求 `resources.arsc` 必须是 **未压缩（stored）+ 4 字节对齐**。
我最初用 `jar uf` 把 `classes.dex` 追加进 APK，`jar` 会重新压缩所有条目，
于是 `resources.arsc` 被压成 deflate、对齐也丢了：

```
aapt2 原始产物:  resources.arsc  method=stored   dataOffset=4648   align4=true
jar 重打包后:    resources.arsc  method=deflate  dataOffset=12621  align4=false   <== 被拒装
```

修复方式是不再用 `jar`，而是自写 zip 写出器（`zip-tool.js` + `assemble-apk.js`）：
- 强制 `resources.arsc` 为 stored，并用 local header 的 extra 字段做 4 字节对齐
- 顺带在组装阶段修正 1.5 的反斜杠条目名
- 追加 `classes.dex`

`check-apk.js` 已把这条加进构建门禁：`resources.arsc` 一旦被压缩或未对齐，
构建立刻失败，不会等到装到手机上才发现。

### 1.7 不能用 file:// 装载页面（会导致页面无样式、登录无响应）

我最初把页面地址写成 `file:///android_asset/www/index.html`。现代 WebView 把
`file://` 当作**不透明来源**，该来源下：

- 子资源（`style.css`、`app.js`）被当跨域拒绝 → **页面完全没有样式**
- `fetch('/api/...')` 同样被拒 → **点登录没有反应**

真机截图确认过这个现象：按钮是系统默认样式、表单挤在一起、登录页与课表页同时可见。

修复：改用一个 **HTTP 虚拟源**装载页面 —— `http://appassets.androidplatform.net/www/index.html`。
这样页面就是一个正常的 Web 来源，同源子资源与 fetch 都正常；而所有请求仍然
会被 `shouldInterceptRequest` 全部拦下由本地处理，**不会真的联网**。
（用 http 而非 https：虚拟域名没有真实证书，走 http 可完全避开证书校验分支；
这里也不需要 secure-context 的 API。思路同 AndroidX 的 `WebViewAssetLoader`，
只是不引入依赖、自行实现。）

> 顺带修好的另一个隐患：`file://` 来源下 `localStorage` 是被禁用的，
> 而页面用它记住学号 —— 在 `file://` 下每次都要重新输入。

### 1.8 登录为什么必须走 JavaScript 桥（又一个大坑）

**结论：`shouldInterceptRequest` 拿不到 POST 请求体，永远拿不到。**

一开始我按"页面把账号密码 POST 给 `/api/login`，拦截层读出来交给原生"来设计，
以为 `WebResourceRequest.getInputStream()` 能读到。编译时才发现 android.jar 里
这个接口根本没有这个方法：

```
public interface android.webkit.WebResourceRequest {
  android.net.Uri getUrl();
  boolean isForMainFrame();
  boolean isRedirect();
  boolean hasGesture();
  java.lang.String getMethod();
  java.util.Map<java.lang.String,java.lang.String> getRequestHeaders();
}
```

去查了官方 issue，确认这是**有意不支持**的
（[issue 119844519](https://issuetracker.google.com/issues/119844519)）；
后来 Android 补的 `WebViewClient.shouldInterceptRequest` 依然只给 URL 和请求头。
也就是说"页面提交的密码"在拦截层是**不可见**的，这条路没有变通写法。

所以登录改走 JavaScript 桥（`AndroidBridge`）。这在本项目里几乎不增加成本——
页面本来就跟 App 在同一个 WebView 里，不需要跨进程。

### 1.9 桥方法里不能等网络（否则页面卡死）

`@JavascriptInterface` 方法运行在 WebView 的 **JavaBridge 线程**上。
如果在里面同步等一次登录（几百毫秒到几十秒），**页面的 JS 会一起被卡住**，
连"登录中…"都动不了。

所以约定是"**发起 + 轮询**"：

```
页面表单提交
   ├─ AndroidBridge.startLogin(json)   ← 立即返回，只把凭据交给原生
   ├─ POST /api/login-start            ← 让原生侧为这次尝试建一条可查询的记录
   └─ 轮询 /api/login-result           ← 每 400ms 一次，直到 ok / failed / 超时
```

状态记在 `LoginState`（纯 Java，不依赖 Android，因此可被桌面测试覆盖）。

> 这里有个容易踩的点：`startLogin` 与 `POST /api/login-start` 是**两步**。
> 只调桥不调 POST 的话，`/api/login-result` 永远停在 `idle`——
> 我第一版就是这么写的，结果是"点了登录一直转圈"。
> 现在这两步绑在一起，并且预览探针会整体验证一遍这条链路（见第六节）。

### 1.10 一个操作只能有一个登录界面

早先的实现是：页面自己有一个登录表单，原生又叠了一层全屏登录界面。
结果是同一个操作下**出现两版登录窗口**（截图确认过）。

现在只有页面这一套界面，原生不再画任何登录控件。`MainActivity` 里只剩下
一个转圈进度条（`ProgressBar`），用于自动登录那几百毫秒。

### 1.11 首帧会闪一下两套界面

页面里"登录"和"课表"两块默认都带 `hidden`，到底显示哪一块要等 `/api/state` 的
结果。但从 HTML 解析完成到 `app.js` 执行之间有一帧，浏览器可能已经渲染过——
手机上会看到"登录页和课表页同时出现"闪一下，看起来也像"两版登录窗口"。

修法是 `boot.js`（在 `<head>` 里**同步**执行，早于内容渲染）先给 `<html>`
打上标记，CSS 用它把两块都藏住；等页面决定好显示哪块再摘掉标记。
另有一个 3 秒兜底定时器：万一 `app.js` 因故没跑起来，页面也不会永远空白。

### 1.12 flex 子项默认会被压扁，导致跨节次课程块"填不满"

**现象**：真机截图里，周一第 5-6 节都有课，但色块只盖住第 5 节，
第 6 节看起来是空的。同一门课的上课时间里明明写着 `13:50-15:25`。

**根因**：跨节次的卡片不是"占两个格子"，而是放在起始那一个 `.slot` 里、
用内联 `height` 撑高、再向下溢出到后续格子上的。而 `.slot` 是 flex 容器
（`display:flex; flex-direction:column`），`.blk` 是它的 flex 子项，
**flex 子项默认 `flex-shrink:1`** —— 浏览器于是把内联的 `151px`
压缩到单行高度。实测：

```
修复前:  节次 5-6  卡高 73px / 应覆盖 152px   填充率 48%
         节次 9-11 卡高 73px / 应覆盖 228px   填充率 32%
修复后:  节次 5-6  卡高 151px / 应覆盖 152px  填充率 99%
         节次 9-11 卡高 228px / 应覆盖 228px  填充率 100%
```

**修法**：给 `.blk` 加 `flex: 0 0 auto`（"不伸不缩，按我给的高度来"）。

> 这个 bug 靠读代码看不出来，靠看截图只能"觉得不对"。是 `preview.js` 的
> 几何探针量出"卡高 / 应覆盖高度 = 48%"才定位到的——所以那条断言留在测试里，
> 见第六节的跨节次回归检查。

### 1.13 表头左角没设宽度，星期整排会横向错位

**现象**：表头上的"一、二、三…"和下面的课程列对不齐，越靠左错得越多。

**根因**：表头左角 `.grid-head .g-time`（显示"第N周"）**只设了 height，没设宽度**，
于是宽度由内容撑开——实测只有 **45px**；而正文左栏 `.grid-times` 是
`var(--time-w)` = **52px**。表头因此整排左移，差值随列推进递减：

```
修复前:  周一 表头[45,96]   列[60,109]  左差 15px
         周日 表头[353,404] 列[355,404] 左差  2px
修复后:  周一 表头[60,96]   列[60,96]   左差  0px
         周日 表头[276,312] 列[276,312] 左差  0px   （7 列全部 0px）
```

**修法**：让表头左角用与正文左栏**完全相同的宽度来源**：

```css
.grid-head .g-time { flex: 0 0 var(--time-w); width: var(--time-w); }
```

**教训**：这种"两处本该同宽、却各自算宽度"的结构，一定要让它们引用同一个变量。
`--time-w` 就是为此存在的，漏用一处就会错位。

### 2. 业务路径里的 `*` 必须原样发送


`/gsapp/sys/yddwdkbapp/*default/index.do` 里的 `*` 一旦被 URL 编码成 `%2A`，
服务端直接返回 403（一张 GIF，很容易误判成"没权限"）。Java 的 `URL` 不会
编码它，这一点我在 `UrlTest` 里单独验证过。

### 3. 登录后必须先取一次应用首页（最关键的坑）

登录拿到 ticket 之后，**必须在应用域上真正取到一次首页**。前置的安全设备会
在这一步下发长效的 `_WEU` 令牌；缺少它时，后续所有业务接口都会返回 403。

我用对照实验确认了这一点（`test/SequenceTest.java`）：

| 时序 | 结果 |
| --- | --- |
| 登录 → 直接调接口 | **403**（GIF） |
| 登录 → 取一次应用首页 → 调接口 | **200** |

实测 `_WEU` 长度会从 120 字符刷新到 332 字符。所以 `School.ensureWarm()`
在首次业务调用前强制走一次首页，被拒时还会再重试一轮。

---

## 一点五、关于 EncryptedSharedPreferences

目标里写的是 `EncryptedSharedPreferences`。**实测这台机器装不了它**，原因具体如下：

- `androidx.security:security-crypto` 的运行时依赖是
  `com.google.crypto.tink:tink-android`（1.0.0 用 1.5.0，1.1.0-alpha06 用 1.7.0）；
- `tink-android` **只发布在 Google Maven**（`dl.google.com` / `maven.google.com`），
  这两个域名在本网络下实测 **超时 / ECONNRESET**，Aliyun、腾讯云镜像里都没有它。

所以本项目采用了**机制等价的自实现** `EncryptedPrefs`：
`AndroidKeyStore` 里的 AES-256-GCM 主密钥 + 加密后写入 `SharedPreferences`
—— 这正是 `EncryptedSharedPreferences` 内部做的事（它的作用就是给
`SharedPreferences` 套一层 Keystore 主密钥加密）。区别只在"谁写的代码"，
安全性上等价，而且 APK 不必背上 Tink（约 1.2 MB）与 Gson。

如果哪天能访问 Google Maven，只要把 `EncryptedPrefs` 换成
`EncryptedSharedPreferences.create(...)` 并把 AAR 加进构建脚本即可，
`CredentialStore` 接口让这个替换是局部改动。

---

## 二、桌面小组件

小组件显示**整周课表缩略图**：横轴周一到周日、纵轴节次，课程块带颜色，
当前周会高亮"今天"这一列。

### 为什么是"画成一张图"

小组件只能用 `RemoteViews`，那套受限控件里**塞不进 WebView**，
也不适合摆几十个 TextView（跨进程更新的开销很大，而且放不下完整网格）。

所以做法是：用 `WidgetRender` 把课表**画成一张位图**，
小组件里只放一个 `ImageView`。既保留了完整周视图的观感，又只有一次跨进程传输。

位图尺寸会按小组件在桌面上被拉伸后的实际大小动态生成
（`OPTION_APPWIDGET_MIN_WIDTH/MAX_HEIGHT`），并收敛到 1200x900 以内，
避免触发 RemoteViews 的 Bitmap 体积上限。

### 它怎么保持数据是新的

| 时机 | 行为 |
| --- | --- |
| 每天 06:30 / 09:30 / 12:30 / 15:30 / 18:30 / 21:30 | `AlarmManager` 定时刷新 |
| 打开 App 时 | 立即刷新一次，并重排闹钟 |
| 登录成功 / 退出登录后 | 立即刷新 |
| 点小组件右上角"刷新" | 立即重新取数 |
| 点小组件其它区域 | 打开 App |
| 手机重启 / App 升级后 | `BootReceiver` 重排闹钟 |

刷新时的取数顺序是**「今天的缓存 → 联网」**：如果缓存已经是今天的，
就直接跳过联网（学校那台服务器不快，能省则省）。
联网失败时不会把小组件变空白，而是**继续显示旧数据**并标注"数据可能不是最新"。

> 没用 `appwidget-provider` 的 `updatePeriodMillis`：系统最短只允许 30 分钟，
> 而且不保证准时，所以定时刷新交给 `AlarmManager`。

### 会话怎么活下来的

小组件在后台刷新时，进程可能是全新的，内存里没有会话。所以加了
`KbStore` 接口 + `FileKbStore` 实现，把 cookie 落到 App 私有目录
（`/data/data/<pkg>/files/session.dat`，其他应用读不到）。

这样正常情况下小组件**不需要重新登录**就能直接调接口；
只有会话真的失效时才用保存的凭据重新走一次 CAS。

> 把 `KbStore` 抽成接口还有个好处：`Http` / `School` 这些网络逻辑
> **完全不依赖 Android API**，所以能在桌面 JVM 上直接跑回归测试。

### 添加方式

在桌面长按空白处 → 「小部件 / 小组件」→ 找到「gzhu课表」→ 拖到桌面。
默认约 4x4 格，可自由拉伸缩放（缩放后会自动重新排版）。

---

## 三、安装与使用

### 安装

从 [Releases](../../releases) 下载最新 APK，传到手机（微信/QQ/数据线均可）点击安装。
也可以装自己 `out/` 里刚构建出来的那份（文件名带构建号，一眼看出是哪一次打包）。
首次安装需要在系统设置里允许"安装未知来源应用"。

- 最低 Android 5.0（API 21），目标 API 34
- 需要网络权限
- **升级要用同一个签名**：直接覆盖安装即可；若提示签名不一致，说明换了 keystore，
  只能卸载重装（会丢掉已保存的密码）

### 使用

1. 打开 App：如果本机已保存过密码，会**自动登录直接进课表**；否则显示登录页
2. 输入学号与密码。默认勾选"同时记住密码"，密码由 Android Keystore 里的
   AES-256-GCM 密钥加密保存（不勾选则只在本次运行有效）
3. 进入课表：可切学期、左右翻周、点课程卡片看详情（教室/教师/周次/教学班）
4. **课表左栏**显示每节课的节次与**上课/下课时间**（如 `1 / 08:30 / 09:15`）

> 登录页底部的"构建 xxx（buildN）"是固定的，登录与否都看得到——
> 出问题时报这一行就能确认装的是哪一次构建。

---

## 四、自己重新构建

```bash
cd android
node build-apk.js
```

脚本会依次做：`aapt2 compile` → `aapt2 link` → `javac` → `d8` →
**自行组装（修正条目名 + 保证 `resources.arsc` 未压缩且 4 字节对齐）** →
`zipalign` → `apksigner` → 打包检查，产物在 `out/`。

### 每次构建的产物都不同（版本号怎么来的）

每次构建会分配一个**自增的构建号**，所以不会出现"两份 APK 分不清是哪次"：

| 项目 | 示例值 | 说明 |
| --- | --- | --- |
| versionName | `1.1.7` | `<基础版本>.<构建号>`，手机"应用信息"里可见 |
| versionCode | `1000007` | 基数 1000000 + 构建号，**单调递增**，保证能覆盖安装 |
| 文件名 | `gzhu-kb-1.1-build7.apk` | 一眼看出是哪一次 |
| 登录页底部 | `构建 1.1.7（build7）` | 打开 App 就能确认装的是哪一份 |

构建号记在 **`build-number.json`**：

```json
{ "build": 7, "lastAt": "...", "lastVersionName": "1.1.7" }
```

> ⚠️ **别删这个文件**。删掉后编号从 1 重新开始，`versionCode` 随之变小，
> 手机上可能"无法覆盖安装"（Android 拒绝降级）。

`BASE_VERSION` 在 `build-apk.js` 顶部，功能大改动时手动加 0.1；
日常重新打包不用管它。

**前置条件**：

| 依赖 | 位置 | 怎么来 |
| --- | --- | --- |
| Android SDK build-tools 34.0.0 | `android/sdk/build-tools/34.0.0` | 见下 |
| Android platform 34 | `android/sdk/platforms/android-34` | 见下 |
| JDK 17（要 JDK，不是 JRE） | 任意位置 | 脚本按 `JAVA_HOME` 环境变量找，也会自动试常见安装路径 |

`android/sdk/` 不进仓库（几百 MB，且各平台不同）。请自己用 Android 官方的
`sdkmanager` 装以下两项到 `android/sdk/`：

```bash
sdkmanager "build-tools;34.0.0" "platforms;android-34"
# 需要的话用 --sdk_root=<仓库>/android/sdk 指定安装位置
```

JDK 找不到时脚本会直接报错并告诉你怎么设 `JAVA_HOME`，不会静默用错版本。
仓库里**没有任何写死的机器路径**——`android/paths.js` 统一负责解析。

签名用的 keystore 是 `keystore/gzhu-kb.jks`（首次构建时自动生成）。
**升级 App 时必须用同一个 keystore**，否则无法覆盖安装。

> **keystore 与它的口令都不在仓库里**（见 `.gitignore`）——这是发布用的签名私钥，
> 泄露意味着别人可以签出能被你老用户"覆盖安装"的更新。第一次构建时脚本会
> 随机生成一个口令并写进 `keystore/keystore-pass.txt`；也可以改用环境变量
> `GZHU_KS_PASS` 指定。请自己生成一份新的 keystore 与口令，
> 不要把开发期这一份用于公开发布。

### 构建时的两个环境坑（已解决，记录备查）

1. **中文路径**：`aapt2` 等 Windows 原生程序打不开含中文的绝对路径
   （路径里的非 ASCII 字符会因 ANSI 代码页转换失败）。解决办法是把 `cwd` 设为
   项目目录、**命令行参数只用相对路径**——所以把仓库放在中文目录下也没问题。
   （另见上一节 1.5：aapt2 还有 assets 条目名反斜杠的问题，构建脚本已自动修正并设门禁。）
2. **子进程输出**：本机沙箱禁止 Node 用管道捕获子进程输出（EPERM），且
   Windows 上无法直接 `spawn` `.bat`。解决办法是输出重定向到文件 +
   经 `cmd.exe /c` 调用批处理。

---

## 五、代码结构

```
android/
├── build-apk.js                     APK 构建脚本（无需 Gradle）
├── build-version.js                 构建号分配（保证每次产物都不同）
├── build-number.json                构建号记录（别删）
├── assemble-apk.js                  自行组装 APK（改条目名 + 保证对齐）
├── zip-tool.js                      极简 zip 读写（零依赖）
├── check-apk.js                     打包门禁（构建期拦截安装失败类问题）
├── check-install.js                 安装兼容性诊断
├── adb-install.js                   用 adb 安装并抓日志（真机排障）
├── web-assets.js                    前端同步（构建与测试共用，避免测试拿旧代码）
├── preview.js                       本地预览 + 布局/登录流程探针（假数据）
├── run-shell-test.js                请求拦截链路测试入口
├── run-layout-test.js               小组件排版单元测试入口
├── run-des-test.js                  DES 向量校验入口
├── run-live-check.js                真实账号联调（异步登录 + 取课表）
├── app/
│   ├── AndroidManifest.xml
│   ├── res/
│   │   ├── layout/widget_kb.xml     小组件布局（标题 + 一张图）
│   │   ├── xml/widget_kb_info.xml   小组件元数据（尺寸、缩放）
│   │   ├── drawable/widget_bg.xml   圆角白底
│   │   ├── mipmap-*/                图标（脚本生成）
│   │   └── values/strings.xml
│   ├── assets/www/                  网页资源（从 server/public 同步）
│   └── src/cn/edu/gzhu/kb/
│       ├── MainActivity.java        单 Activity：WebView 拦截 + 登录桥
│       ├── WebShell.java            shouldInterceptRequest 的响应实现
│       ├── WebRouter.java           路由决策（纯 Java，可单测）
│       ├── KbRepository.java        取数 / 缓存 / 会话失效自动重登 / 异步登录
│       ├── LoginState.java          一次异步登录的进度与结果（纯 Java，可单测）
│       ├── AndroidRepo.java         依赖 android.jar 的装配点
│       ├── EncryptedPrefs.java      Keystore 加密的凭据存储
│       ├── CredentialStore.java     凭据存储接口（让仓储层不依赖 Android）
│       ├── FileKbStore.java         会话 cookie 落盘
│       ├── KbStore.java             cookie 存储接口
│       ├── MemoryStores.java        内存实现（供测试注入）
│       ├── KbWidgetProvider.java    桌面小组件
│       ├── WidgetRender.java        课表 -> 位图
│       ├── WidgetLayout.java        排版计算（纯 Java，可单测）
│       ├── WidgetScheduler.java     定时刷新（AlarmManager）
│       ├── BootReceiver.java        开机后重排闹钟
│       ├── School.java              CAS 登录与课表接口（移植自 server.js）
│       ├── Http.java                带 cookie jar 与重试的 HTTP 客户端
│       ├── Des.java                 正方 strEnc 的 DES 实现
│       ├── KbModel.java             按节次合并成课表网格
│       └── Json.java                极小 JSON 库
└── out/gzhu-kb-1.1-buildN.apk       构建产物（只保留最新一份，不进仓库）
```

### 网页资源同步

`app/assets/www/` 下的 `index.html` / `style.css` / `app.js` / `boot.js`
由 `web-assets.js` 从 `../server/public/` 复制并注入版本号。
**改 UI 只需改 `server/public/`，然后重新构建**，两边始终一致。

构建脚本与请求拦截测试都调用同一个同步函数，所以不会出现"测试用的是上一次
打包留下的旧前端"这种假通过。

---

## 六、验证情况

### DES 加密（这是登录能否成功的关键）

`Des.java` 必须与校方 `des.js` 的 `strEnc` **逐位一致**。已用 11 组测试向量
（含中文、特殊字符、各种长度）全部通过：

```bash
cd android
node gen-vectors.js                 # 用 des.js 生成期望值（一般不用重跑）
node run-des-test.js                # 11/11 通过
```

> 单独做成一个入口，是因为编译产物在 `build/` 下，而 `build-apk.js` 会重建
> `build/`——打包后直接再跑一次这个脚本即可（它自己会重新编译）。

移植过程中踩到的坑（都记在 `Des.java` 注释里）：

- `PC_1` / `PC_2` 不是标准 DES 表，而是 `des.js` 里按
  `key[8i+j] = keyByte[8*(7-j)+i]` 生成的自定义排列
- `P` / `FP`（`IP_1`）是 **0-based** 手写位索引表，与教科书版本不同
- `bt64ToHex` 每 4 位对应 **1 个**十六进制字符（我一开始写成了 2 个）

### 端到端逻辑（在桌面 JVM 上跑真实请求）

`test/AndroidLogicTest.java` 复用 Android 端**同一套** `School` / `KbModel`
代码（这两部分不依赖 Android API），实测结果：

```
[1] 登录成功  姓名=（登录用户姓名） 学号=2000000000  (556ms)
[2] 学期 24 个
[3] 当前第 4 周，学期 20261，本周日期 7 天
[4] 节次方案 11 节：1[08:30-09:15] ... 11[20:00-20:45]
[5] 课表原始记录 62 条
[6] 第 4 周合并后课程块 10 个（合并率 84%）
[7] 网格 7 列 x 11 行；重叠格 0 个
[8] 第 1 周 0 个块，第 8 周 10 个块（周次位图过滤生效）
```

输出与服务器版（`server/`）完全一致，说明两套实现行为一致。

### 请求拦截链路（用 Android 桩件在 JVM 上跑真实代码）

`test/stubs/` 里放了 `android.util.Log` / `Context` / `AssetManager` /
`WebResourceResponse` 四个桩件，于是 `WebShell.intercept()` 能在桌面 JVM 上
真正执行 —— 替换的只是系统类，业务代码是 App 里那一份。共 40 余项断言全通过：

```
[1] 页面与静态资源        index.html / style.css / app.js / boot.js 均能从 assets
                          读出，MIME 正确，内容确实是课表页（含 login-form）
[2] 接口路由              未登录 /api/kb -> 401；POST /api/login 被明确拒绝并说明原因；
                          /api/login-start 缺字段 -> 400 且文案明确；
                          /api/login-result 初始必须是 idle（不能一上来就说成功）
[3] 路径穿越防护          ../../secret.js 与编码穿越都被拒
[4] 前后端接口一致性      从 app.js 里抓出实际调用的 /api 路径，
                          逐个核对原生都能处理（防止改前端忘改原生）
[5] 版本号已注入
[6] 首帧防闪与登录页清理  boot.js 同步引入、两块默认 hidden、只有一套登录界面、
                          页面经桥发起登录并轮询结果
[7] 左栏时间              确认用到了 kssj 与 jssj（上课与下课时间）
```

> 这些断言读的是 `app/assets/www`，而它是构建时从 `server/public` 同步过来的。
> 所以 `run-shell-test.js` 会**先自动同步一次**——否则改了前端没重新打包，
> 测试会拿旧代码给出"通过"，等于没测。

运行方式：

```bash
cd android
node run-shell-test.js
```

### 真实账号联调（异步登录 + 取课表）

前面的测试都靠桩件，证明不了"这套逻辑真能连上学校系统"。所以另有一个
用真账号跑全链路的检查（复用 App 里真实的 `KbRepository` / `School` / `Http`，
只把存储换成内存实现）：

```bash
cd android
set GZHU_USER=2000000000
set GZHU_PASS=...
node run-live-check.js
```

实测输出（本次）：

```
[1] 初始状态 idle
[2] 发起异步登录 -> 立即 running（桥不阻塞）；轮询 1216ms 后 phase=OK（姓名已取到）
[3] 学期 24 个，当前学期 20261
[4] 第 5 周 / 共 30 周，节次 11 个，课程块 11 个
[5] 左栏时间齐全：首节 1 [08:30-09:15]  末节 11 [20:00-20:45]   ← 本次需求核心
[6] 免登录：新仓库用保存的凭据直接取到课表
[7] 退出登录后凭据被清掉，状态回到 idle
```

### 页面布局与登录流程（headless 浏览器探针）

布局只能靠"看"，但"看截图"容易看错。所以 `preview.js` 用 headless 浏览器
**量出真实像素**再判断：

```bash
cd android
node preview.js            # 起本地预览（假数据，不连学校），浏览器里看
node preview.js --probe    # 在 320/360/400/412 四种视口下量布局并断言
node preview.js --probe --shot   # 顺便截图到 build/preview-*.png
```

它检查的是会真实影响手机使用的问题：

- 文档宽 > 视口宽 → 课表要左右滚动
- 课程卡 / 固定版本号超出视口
- 左栏格缺上课或下课时间、内容被裁切
- 登录页与课表页**同时可见**（也就是"两版登录窗口"的观感）
- 走一遍真实登录：填表 → 桥 → 轮询 → 进课表，确认能切换且课表渲染出来

> 踩过的坑：**不能用 `--window-size` 控制视口**。实测在 headless 下它不起作用
> （请求 320/360/400 三种宽度，`window.innerWidth` 全是 500），那样"测了三个宽度"
> 其实是同一个宽度测三遍。现在改用 DevTools 协议的
> `Emulation.setDeviceMetricsOverride` 真正改视口，并断言 `innerWidth` 与预期一致。


### 小组件排版（纯计算单元测试）

`WidgetLayout` 特意做成不依赖 Android API，因此可直接单测。共 21 项断言全通过：

```
[1] 基本几何 OK  (500x420, 列宽 65)         行高总和铺满、最后一行贴底
[2] 跨节次高度 OK  (跨3节=103px, 单节=33px) 跨节次块高度=所覆各行之和
[3] 密集课表无重叠无越界 OK  (42 个块)      7 列 x 6 块全排布，0 重叠 0 越界
[4] 极小尺寸安全 OK                          240x160 / 120x90 / 60x40 均无非法块
[5] 空数据安全 OK                            null 与空课表都不崩
[6] 13 节排版 OK  (行高 30px)               节次数变化时行高仍铺满且为正
```

运行方式：

```bash
cd android
node run-layout-test.js
```

---

## 七、真机排障（adb）

手机安装器只给一句笼统提示，用 adb 能拿到确切原因：

```bash
cd android
node adb-install.js              # 装 out/ 里最新的 APK，输出失败原因
node adb-install.js --reinstall  # 先卸载再装（解决签名冲突/降级）
node adb-install.js --logcat     # 装完持续抓 GzhuKB 日志，边看边操作 App
```

前置：手机连 USB、开启「开发者选项 → USB 调试」、手机上允许调试授权。

常见失败码对照：

| adb 报错 | 含义 |
| --- | --- |
| INSTALL_FAILED_UPDATE_INCOMPATIBLE | 签名与已装版本不一致 → --reinstall |
| INSTALL_FAILED_VERSION_DOWNGRADE | 目标版本更低 → --reinstall |
| INSTALL_PARSE_FAILED_* | 包结构有问题 |

日志里 GzhuKB tag 会打印：每次请求的拦截结果、资产读取失败时**试过的路径与 assets 实际内容**、_WEU 刷新情况、登录耗时。

---

## 八、已知限制

- **校园系统会限速**：短时间内反复登录会触发超时。客户端已内置重试
  （3 次退避 + 预热重试），但不要连续快速登录。
- **只读**：App 只读取课表，不向学校系统写入任何数据。
- **签名证书有效期 30 年**，但目录里的 keystore 是开发用自签名证书，
  仅适合个人安装；如果要在应用商店分发需要另办证书。
- **上游改版**：CAS 登录页字段（`lt`/`execution`）或课表字段若变更，
  改动集中在 `School.java`，加密算法部分见 `Des.java`。
- **登录凭据只能经 JavaScript 桥传入原生**（见 1.8）。这是 WebView 的
  硬限制，不是设计取舍；如果以后 Android 补上了读取 POST 请求体的能力，
  可以改回"页面直接 POST"。

### 关于 320px 窄屏

课表是 7 列固定布局，窄屏上每列只有约 36px。实测 320px 下不产生横向滚动，
但课程名会换行较多。已在 `style.css` 里把课表左右外边距从 12px 收到 8px、
左栏定为 52px 来尽量让出空间；再窄的屏幕（<320px）没有实测。

