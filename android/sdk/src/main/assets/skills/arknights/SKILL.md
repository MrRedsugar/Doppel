---
name: arknights
description: 明日方舟操作步骤：启动游戏，打开终端与主线章节，寻找1-7或TR-9；1-7可按现场记录设置代理，TR-9按手动教学流程进入战斗并推进到本次结算。
platforms: [android]
packages: [com.hypergryph.arknights, com.hypergryph.arknights.bilibili]
app_aliases: [明日方舟, 方舟, Arknights]
aliases: [明日方舟, 方舟, Arknights, 终端, 主线, 章节, 关卡, TR-9, 教学, 理智, 代理, 部署, 战斗]
dependencies:
  tools: [action]
version: '2026.09.13.2'
---

# 明日方舟操作入口

从当前页面接着操作，不因加载Skill就返回主界面重走。每次看新图确认进入下一页；点击已被接受不代表页面已切换。

去1-7/TR-9：主界面→终端→主线“曲谱”（Main Story/主题曲）→“为了明日”→乐章收录中找EP01→前往章节→章节地图→点目标关卡。详细入口、找不到时如何滑动、停在哪一页，先读references/navigation.md。两关都找EP01，不选“方舟”故事线或第十二章；“方舟”前是标志，不是章节数字。

- 看到START还未进入大厅：读references/startup-state.md，点启动入口再等大厅。
- 要设置代理倍数并刷关：读references/stages-and-replay.md，核对目标、倍数、消耗和本次结果。
- 要手动战斗：正在运行且有暂停控件才点暂停；已暂停/选朝向就保留，已结算先处理结果。用read_skill_resource一次读name=arknights-deployment、path=references/combat-handbook.md全文，按当前阶段继续部署、技能和战斗；已有knowledge中的同版全文复用，自动摘录不算全文。
- 战斗操作失败：读name=arknights-deployment、path=references/battle-response.md；场上有人或卡栏空时从当前局势继续，别把已部署事实改写成“没部署”。

每个发给B的动作完整说明对象、所在位置、目标地块/方向和下一步可见结果。任务只要求进关卡时停在详情/准备页；要求通关时必须看到本次胜利结算。本文改写后的完整流程仍未验证，不能因旧记录成功就替当前任务报完成。
