<div align="center">

# 🦌 鹿鹿 (Lulu) · 端侧智能求职 Agent
### 首款专为现代求职者打造的 Android 原生 Multi-Agent 治愈系求职搭子

<p align="center">
  <img src="https://img.shields.io/badge/Language-Kotlin_100%25-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Platform-Android_Native-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/AI_Engine-DeepSeek_R1%2FV3-4D6BFE?style=for-the-badge" />
  <img src="https://img.shields.io/badge/Architecture-Multi--Agent_%2B_FSM-FF6B6B?style=for-the-badge" />
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue?style=for-the-badge" />
</p>

<p align="center">
  <strong>“懂技术的自动化推土机，更懂职场人的温柔治愈系求职搭子。”</strong>
</p>

<p align="center">
  <a href="#-界面一览">界面一览</a> •
  <a href="#-为什么选择鹿鹿">为什么不同</a> •
  <a href="#-系统架构设计">架构设计</a> •
  <a href="#-极速安装使用">极速上手</a> •
  <a href="#-后续规划路线-roadmap">后续规划</a> •
  <a href="#-常见问题与免责声明">免责声明</a>
</p>

---

</div>

## 📱 界面一览

<p align="center">
  <img src="docs/lulu_home.png" width="340" alt="鹿鹿主页控制台" />
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="docs/lulu_records.png" width="340" alt="小鹿探路手账" />
</p>

<p align="center">
  <sub>左：极简纯净的求职主控台 &nbsp;|&nbsp; 右：温润如初的小鹿探路手账</sub>
</p>

---

## 💡 为什么选择 鹿鹿 (Lulu)？

市面上 99% 的自动求职工具都是粗糙的浏览器脚本（Chrome 插件/Playwright），面临着 **Web 端风控频繁封号、Cookie 动辄失效、配置繁琐、无法感知移动端 HR 真实状态** 的硬伤。

**鹿鹿 (Lulu)** 彻底改变了这一切：
- 📱 **真机 Android 原生驱动**：运行在真实手机端，三阶贝塞尔拟人轨迹手势 + 高斯微小抖动，极大降低风控封号风险。
- 🧠 **端侧多智能体协同（Multi-Agent）**：Scout（侦察）、Evaluator（评估）、Supervisor（风控）、Communicator（破冰）各司其职。
- 🌸 **专为求职者打造的情绪缓冲**：不只是冰冷的投递工具，更是为你挡在前面、安抚内耗、给出暖心研判评语的治愈搭子。
- 💰 **极致的 Token 成本控制**：内置本地规则过滤器（0-Token 消耗）+ 岗位指纹 MD5 去重缓存，拒绝盲目消耗 API。
- 🛡️ **100% 隐私与纯端侧安全**：无中心化数据服务器，简历与 API Key 均存储在手机本地加密容器（EncryptedDataStore）。

---

## 🏗️ 系统架构设计 (Clean Architecture & Multi-Agent)

鹿鹿采用高内聚、低耦合的模块化分层架构，各智能体协同运作，由调度中枢与有限状态机（FSM）严格控制生命周期与自愈流程：

```text
app/src/main/java/com/lulu/agent/
├── accessibility/         // 【底层驱动层】DFS节点遍历即时回收 (防OOM)、贝塞尔拟人手势引擎
├── dispatcher/            // 【调度中枢层】有限状态机 (FSM)、广播解耦与事件契约、定向复位
├── agent/                 // 【多智能体集群】
│   ├── supervisor/        // 🛡️ 安全风控官：验证码拦截、连续失败熔断、场景守卫
│   ├── scout/             // 🔍 感知工兵：页面巡查、卡片几何排版解析、JD 文本清洗
│   ├── evaluator/         // 📊 评估参谋：本地 0-Token 过滤 + DeepSeek 深度契合度研判
│   └── communicator/      // 💬 谈判代表：个性化破冰语构思、自动化填词与气泡送达校验
├── llm/                   // 【认知支撑层】DeepSeek 客户端、指数退避重试、Token 每日预算熔断
├── floating/              // 【悬浮呈现层】双模态 HUD (灵动胶囊小药丸 + 极客大控制台)
└── data/                  // 【持久化层】Room 数据库 (岗位去重与流水)、EncryptedDataStore (加密配置)
```

---

## 🖥️ 双模态自适应悬浮交互 (Floating HUD)

- 🟢 **灵动胶囊模式（Capsule）**：贴边自动吸附，以呼吸灯状态静默展示 Agent 当前步骤（扫描中 / 思考中 / 沟通中），手势期间自动开启触摸穿透（FLAG_NOT_TOUCHABLE）。
- 🖥️ **极客控制台模式（Console）**：展开后以打字机流式实时呈现 DeepSeek 的思考链、风险评估打分及系统日志，支持随时挂起或一键急停。

---

## 🚀 极速上手 (Ready to Use)

鹿鹿**无需任何电脑端配置环境**，开箱即用：

1. **下载安装**：前往 [Releases 页面](../../releases) 下载最新的 `LuluAgent-v1.0.0.apk` 安装到 Android 手机（Android 8.0+）。
2. **开启权限**：首次打开应用，根据首页指引开启 **无障碍权限（敲门开路）** 与 **悬浮窗权限（悄悄支招）**。
3. **配置凭证**：点击右上角齿轮「百宝袋」，填入你的 **DeepSeek API Key**（支持一键连通性测试），并粘贴你的 Markdown 格式简历。
4. **开启探路**：点击主页 **【启动鹿鹿 · 开始探路】**，鹿鹿将自动拉起 Boss 直聘，在后台贴心为你筛选、评估与破冰沟通！

---

## 🗺️ 后续规划路线 (Roadmap)

鹿鹿正在积极持续迭代中，后续版本计划引入：

- [ ] **实时消息回复与持续代聊**：基于 Room 存储与 HR 的历史对话记录，借助大模型实现多轮拟人回复。
- [ ] **E人 / I人 模式分流**：
  - **E人·极速推土机模式**：高效全自动批量打招呼与跟进。
  - **I人·治愈系护航模式**：将高匹配岗位沉淀至每日精选待办清单，提供半自动参谋与多风格话术建议。
- [ ] **面试深度多维分析**：约面成功后，联动公司业务背景、JD 要求与历史沟通线索，智能输出靶向押题与 STAR 答题支架。
- [ ] **针对性简历诊断报告**：根据意向岗位的整体画像，智能评估简历短板并输出优化建议。
- [ ] **多平台适配扩展**：探索接入猎聘、智联招聘等更多求职场景。

---

## ⚠️ 免责声明 (Disclaimer)

1. 本项目仅供 **Android 无障碍技术研究、大模型端侧 Agent 架构探索及个人求职效率提升** 使用，严禁用于任何商业牟利、黑产刷量或恶意滥用行为。
2. 请严格遵守目标招聘平台的服务协议，合理设置投递频率与沟通上限。使用本工具所产生的一切账号风控、限制或连带后果由使用者自行承担，开发者不承担任何直接或连带责任。
3. 本项目为纯端侧应用，所有简历、本地流水及 API Key 均仅保存在用户手机本地，不会向任何未经授权的第三方服务器上传个人隐私。

---

## 🤝 贡献与致谢

欢迎提交 Issue 与 Pull Request，共同完善鹿鹿的自动化能力与治愈体验！

- **LLM Engine**: [DeepSeek](https://www.deepseek.com/)
- **UI Architecture**: Jetpack & Material Components
- **Automation**: Android Accessibility APIs

<div>
  <sub>Made with ❤️ by Developers, for Every Job Hunter. 祝每一位求职者都能早日收获理想 Offer！</sub>
</div>