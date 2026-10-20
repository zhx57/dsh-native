# DSH Native

DeepSeek Harness 的原生 Android 客户端。直接与 DSH 主机通信，不使用 WebView。

## 为什么不是 WebView 套壳

WebView 方案要把整棵 React 组件树、Cordis 客户端模块和 CSS 都搬到手机上再解析执行，
在低端机上滚动和输入都会掉帧。这里改成原生 Compose 渲染，只保留协议：

- **一元 RPC**：`POST /api/<endpoint>`，`client-request` 信封
- **流式**：`WS /api/remote.mux`，`open` / `item` / `end` / `error` 帧
- **认证**：`dsh web` 打印的 `?token=` 一次性换取签名 cookie，之后按 host 复用

一次会话只需要一次列表查询和一条长连接，没有前端框架的运行时开销。

## 界面

设计令牌逐条照抄 `@deepseek-ai/dsh-client-ui-theme`，所以明暗两套配色和 Web 端一致：
底色 `--dsw-alias-bg-base`、侧栏 `--dsw-specific-sidebar-fill`、气泡
`--dsw-specific-bubble`、代码块 `--dsw-alias-markdown-code-block` 等。

小屏适配：

| 项 | 处理 |
| --- | --- |
| 侧栏 | 手机上收进抽屉，对话区拿满宽度 |
| 键盘 | `imePadding`，输入框随键盘上移，导航栏只预留一次 |
| 气泡 | 宽度按屏幕 82% 上限计算，窄屏不换行挤压 |
| 工具卡片 | 折叠态只显示名称 + 一行参数摘要，点开看参数和输出 |
| 推理块 | 默认折叠，流式时显示“思考中…” |
| 代码块 | 横向滚动，不折行破坏缩进 |

## 已实现

- 会话列表：工作区分组、运行状态、最近使用排序
- 对话：markdown（标题、列表、引用、表格、代码块、行内样式）、工具调用卡片、
  推理折叠块、流式增量输出、停止生成
- 发送：排队发送 / 运行中改投（`queue` / `steer`）
- 模型：按 provider 分组的选择器，切换走 `session/selectModel`
- 设置：外观三档、主机信息、断开连接
- 配对：粘贴启动链接，401/403/网络错误分别给可操作提示

## 构建

推送后由 GitHub Actions 编译并发布到 Releases，产物是 `app-release.apk`。

签名密钥 `keystore/dsh-release.jks` 随仓库提交，这样每次构建签名一致，
手机上的旧版本可以直接覆盖升级；如要换成私有密钥，设置 `DSH_KEYSTORE_PATH`、
`DSH_KEYSTORE_PASSWORD`、`DSH_KEY_ALIAS`、`DSH_KEY_PASSWORD` 即可覆盖。

本地构建需要 Android SDK 35 与 JDK 17：

```bash
gradle :app:assembleRelease
```

## 配对

1. 电脑上运行 `dsh web`，记下打印的地址（形如 `http://192.168.1.10:3080/?token=…`）
2. 手机与电脑在同一网络
3. 打开 App，粘贴完整地址，点连接

`127.0.0.1` 在手机上指向手机自己，局域网连接要填电脑的实际 IP。

## 协议依据

端点清单和参数形状取自 DSH 0.2.0-rc.2 安装包里的 Typert 描述符，
并用真实主机逐条验证过；`session/page` 的 `throughSeq` 不能超过会话游标，
所以分页前先读 `asOfSeq`。
