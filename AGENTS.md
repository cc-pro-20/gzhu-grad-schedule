# 项目记忆（AGENTS.md）

这个文件给后续接手的人／AI 用，记录**没法从代码里看出来**的约定与背景。
改代码前先看这里，尤其是「先别动的东西」。

---

## 项目身份

| 项 | 值 |
| --- | --- |
| 仓库 | https://github.com/cc-pro-20/gzhu-grad-schedule |
| GitHub 账号 | `cc-pro-20` |
| 仓库名 | `gzhu-grad-schedule` |
| 应用显示名 | **gzhu课表** |
| 英文名 | GZHU Grad Schedule |
| 平台 | Android（另有同源的 Node 网页版） |

这是一款**广州大学研究生课表** App：手机上登录学校研究生系统看课表，
支持桌面整周课表小组件。**纯本地**，不依赖任何服务器。

GitHub 简介（已在仓库上生效）：

> 广州大学研究生课表安卓 App｜纯本地，登录即用，桌面整周课表小组件｜无服务器、无第三方依赖、凭据 Keystore 加密｜构建不需要 Gradle / Android Studio

建议 topics：`gzhu` `timetable` `schedule` `android` `webview` `appwidget` `keystore` `no-gradle` `chinese-university` `graduate`

### 仓库名的由来（别再改名，也别再想"更好听"的）

当初在几个候选里选了 `gzhu-grad-schedule`，理由是**能被搜到**：
学生找课表时敲的是"广大 课表"，所以 `gzhu` / `grad` / `schedule` 三个词都必须在名字里。

已经排除的名字，原因是真实存在的撞名或歧义：

| 候选 | 为什么不用 |
| --- | --- |
| `kekb`（课课表） | 缩写撞上日本 KEK 的 **KEKB 加速器**，搜索会被论文淹没 |
| `kebiao` | 太泛，已有 `salt-fishes/jiankebiao`、`yankysqiu/kebiao-pdf-to-ics` |
| `keda` | 已有 `schoren/keda`（记账应用） |
| `gzhu-schedule-*` | 已有 `lightyears1998/gzhu-schedule-export` 占了前缀（所以加了 `grad`） |
| `kexia` / `qingkebiao` | 更像品牌但搜不到，与"要被搜到"这个首要目标冲突 |

---

## 工作区结构

仓库根目录下是三个互不完全重叠的部分：

```
<仓库根>/
├── android/    安卓 App：构建脚本 + Java 源码 + 验证脚本（主要成果）
├── server/     Node 网页版（零依赖单文件），前端 public/ 同时是 App 的页面来源
└── recon/      当初抓取/逆向学校系统时的产物，**不进仓库**（见下）
```

`android/paths.js` 负责解析所有外部依赖的位置（JDK 从 `JAVA_HOME` 找、
前端目录按仓库相对位置算），所以**仓库里没有任何写死的机器路径**，
clone 到任何目录、任何平台都能构建。

### ⚠️ 前端只有一份，不是两份

`server/public/` 是页面的**唯一来源**，`android/app/assets/www/` 是构建时由
`android/web-assets.js` 复制过去的产物。**改页面只改 `server/public/`**，
然后重新打包；直接改 `assets/www/` 会在下次构建时被覆盖掉。

---

## 上传 GitHub 前必须注意（重要）

### 1. 凭据泄露：**已排查并修好**（2026-09-28）

**结论：密码从未被推送到 GitHub（远端当时是空的），工作区里的泄露已清除。**

排查与处理过程记录如下，供以后复查（**故意不写出真实值**——本文件在公开仓库里）：

| 项 | 情况 |
| --- | --- |
| 远端仓库状态 | 只有 GitHub 网页创建的 `Initial commit`，树里**仅一个 219 字节的自动生成 README**，无任何项目文件 |
| 因此密码是否外泄 | **没有**。仓库 `size: 0`，历史里没有项目代码 |
| 实际的泄露点 | `android/des-vectors.json` 里有**明文密码**，来源是 `gen-vectors.js` 把真实账号当成了测试向量 |
| 处理 | 已把 `gen-vectors.js` 的用例换成等长假数据，重新生成 `des-vectors.json`；DES 校验仍 11/11 通过 |
| 其它残留 | `WebShellTest.java`、`preview.js`、`README.md`、`run-live-check.js` 里的真实学号也已换成假值 |
| 全部清理完的复查 | 工作区（排除 `build/` `out/` `sdk/` 与 git 历史）搜真实学号与密码 → 无结果 |

**教训（写代码时请守住）**：

- 测试向量、示例数据、截图样本里**一律用假值**。真实账号只从环境变量
  （`GZHU_USER` / `GZHU_PASS`）读，不要写进任何文件。
- 提交前跑一遍全库搜索，确认没有真实凭据：

  ```bash
  # 在仓库根目录执行，把 <你的学号>/<你的密码> 换成实际值
  git ls-files | ForEach-Object { Select-String -Path $_ -Pattern '<你的学号>|<你的密码>' }
  ```

> 补充建议：如果这个仓库曾经在别处提交过带凭据的版本，删文件是没用的
> （历史里还在），**改一次学校密码**最省事也最彻底，成本为零。

### 2. 签名密钥与口令都不能上传

`android/keystore/` 整个目录（keystore 私钥 + `keystore-pass.txt` 口令）都在
`.gitignore` 里。**泄露 = 别人可以冒名签出能被老用户"覆盖安装"的更新。**

`build-apk.js` 已改成从环境变量 `GZHU_KS_PASS` 或本地口令文件读取，
源码里**不再写死口令**。要公开发布请自己生成一份新的 keystore 与口令。

### 3. `recon/` 不要上传

除了含本人学号，`recon/samples.json` 里还有**其他同学与老师的姓名**
（课表响应里的 `JSXM` / `XM` 字段）。这些不是我的信息，
不该由我公开。已被 `.gitignore` 排除。

### 4. APK 不进仓库，走 GitHub Releases

`android/out/*.apk` 已被 `.gitignore` 排除。**发版流程**：

1. `node build-apk.js` 出 APK（本地 `out/` 只保留最新一份）
2. 在 GitHub 建一个 Release，tag 用 `v<versionName>`，例如 `v1.1.21`
3. 把 `out/gzhu-kb-1.1-build21.apk` 传为该 Release 的附件
4. Release 说明里写清改了哪些问题

**为什么不用提交的方式**：APK 是二进制构建产物，git 无法有效增量存储，
每发一版仓库就大几十 KB 且永久留在历史里；几十版之后仓库会到几百 MB，
clone 变得很慢。而且"提交最新 APK"会和"覆盖安装必须签名一致"纠缠在一起——
用户从 Release 下载更直观，也便于写更新说明。

**升级兼容性不变**：只要还用同一个 `keystore/gzhu-kb.jks` 签名，
新旧 APK 就能互相覆盖安装，用户不必卸载（也就不会丢已保存的密码）。

---

## 先别动的东西（改了会出真问题）

| 东西 | 为什么不能动 |
| --- | --- |
| **包名 `cn.edu.gzhu.kb`** | 改了等于换一个应用，手机上无法覆盖安装，用户得先卸载并丢掉已存密码 |
| **`android/keystore/gzhu-kb.jks`** | 换密钥后就无法覆盖安装老版本，只能卸载重装 |
| **`android/build-number.json`** | 删了构建号从 1 重来，`versionCode` 变小 → 无法覆盖安装 |
| **`android/app/assets/www/`** | 是构建产物，手改会被覆盖；改 `server/public/` |

---

## 构建与验证（都在 `android/` 下）

```bash
node build-apk.js        # 出 APK 到 out/，只保留最新一个
node run-des-test.js     # DES 与校方 des.js 逐位一致（11/11）
node run-layout-test.js  # 小组件排版（纯计算）
node run-shell-test.js   # 请求拦截链路（Android 桩件 + 真实业务代码）
node run-live-check.js   # 真实账号联调（需 GZHU_USER / GZHU_PASS）
node run-day-probe.js    # 某一天某几节的数据诊断（查"有课没显示"这类问题）
node preview.js --probe  # headless 浏览器量真实像素，验布局与登录流程
```

前四个不需要网络与凭据，**应始终全绿**；后三个按需跑。

### ⚠️ 行号 0-based，节次码 1-based

排查课表显示问题时**最容易在这里看错**：`KbModel` 里 `from`/`to` 是**数组行号**
（第 1 节 = 行 0），而学校给的 `KSJCDM`/`JSJCDM` 是**从 1 开始的节次码**。
`DayProbe` 现在已经把两种编号都打印出来——曾经因为这个 off-by-one，
把"正常合并"误判成"第 6 节没显示"。

**正常行为备忘**：一节一条记录时（如周一第 5、6 节各一条、同课程同教室），
`KbModel` 会合并成一个 `from=4,to=5,span=2` 的块，渲染成**一张跨两节的卡片**。
所以"只看到一张卡"是对的，不是丢数据。

### ⚠️ `.blk` 的 `flex: 0 0 auto` 不能删（踩过一次）

跨节次卡片是"用内联 height 撑高 + 向下溢出到后续格子"实现的。
`.slot` 是 flex 容器，`.blk` 是 flex 子项，**子项默认 `flex-shrink:1`**，
于是内联的 `151px` 被压回单行 `73px` —— 真机上表现为"第 5-6 节都有课，
但只有第 5 节有色块，第 6 节看着是空的"。

已在 `WebShellTest` 第 8 组加了回归断言（检查 CSS 里有 `flex: 0 0 auto`）。
详见 README 1.12。

**排查这类问题的方法**：不能靠看截图，要用 `preview.js` 的几何探针量
"卡片实际高度 / 应覆盖高度"的填充率（设 `PREVIEW_KB` 用真实数据）。

### ⚠️ 表头左角必须与正文左栏同宽（也踩过一次）

`.grid-head .g-time`（表头"第N周"那格）如果只设 height 不设 width，宽度会被
内容撑开（实测 45px），而正文左栏 `.grid-times` 是 `var(--time-w)`（52px），
于是**整排星期横向错位**，越靠左错得越多（周一差 15px、周日差 2px）。

两处都必须写 `flex: 0 0 var(--time-w); width: var(--time-w);`。
已加回归断言（`WebShellTest` 第 9 组）。

---

## 技术上的硬结论（踩过的坑，别再试）

这几条都写在 `android/README.md` 里了，这里只留索引，避免重复踩：

1. **`shouldInterceptRequest` 永远拿不到 POST 请求体** —— `WebResourceRequest`
   接口里没有 `getInputStream()`（Android 官方 issue 119844519 确认不打算支持）。
   所以登录凭据只能经 `AndroidBridge` 桥传入原生，见 README 1.8。
2. **桥方法里不能等网络** —— `@JavascriptInterface` 跑在 WebView 的 JavaBridge
   线程上，阻塞会把页面 JS 一起卡死。所以是"桥发起 + 页面轮询"，见 README 1.9。
3. **页面不能用 `file://` 装载** —— 现代 WebView 把 `file://` 当不透明来源，
   子资源与 `fetch` 都被拒（表现为"页面没样式、登录没反应"）。改用虚拟源
   `http://appassets.androidplatform.net/`，见 README 1.7。
4. **`resources.arsc` 必须未压缩且 4 字节对齐** —— 否则 Android 11+ 报
   "兼容性问题"拒装；不能用 `jar uf` 打包，见 README 1.6。
5. **aapt2 在 Windows 上会把 assets 条目名写成反斜杠** —— 会导致页面 404，
   构建脚本会自动修正并设门禁，见 README 1.5。
6. **业务路径里的 `*` 不能编码成 `%2A`** —— 会 403，见 README 第 2 节。

---

## 上游系统的已知特性

- 学校服务器**会限速**，短时间内反复登录会超时；不要连续快速登录。
- 登录成功后**必须先取一次应用首页**，否则前置安全设备不下发 `_WEU`，
  后续业务接口全部 403，见 README 第 3 节。
- 加密用校方 `des.js` 的 `strEnc`（硬编码密钥 `'1','2','3'`），
  Java 移植在 `Des.java`，已用 11 组向量校验一致。
