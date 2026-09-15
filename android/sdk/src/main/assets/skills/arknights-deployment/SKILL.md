---
name: arknights-deployment
description: 明日方舟战斗操作：暂停、点选干员、辨认绿色格、拖放和选择朝向；布置各条通路、治疗与远程火力，开启技能、操作装置并推进到结算。
platforms: [android]
packages: [com.hypergryph.arknights, com.hypergryph.arknights.bilibili]
app_aliases: [明日方舟, Arknights]
aliases: [明日方舟, TR-9, 战斗, 暂停, 干员, 部署, 地块, 朝向]
dependencies:
  tools: [action]
version: '2026.09.13.2'
---

# 从暂停操作到战斗结算

先识别当前阶段，接着做下一步；不是每次读到此手册都重选卡、重部署。已在场的单位、没有待部署卡、当前朝向预览都应保留。

首次需要战斗操作时，正在运行且有暂停控件才先暂停；已暂停/选朝向就保留，已结算先处理结果。用read_skill_resource读取name=arknights-deployment、path=references/combat-handbook.md全文；已有knowledge中同版全文则复用，自动摘录不算全文。操作失败再读references/battle-response.md；出处见references/sources.md。

进入战斗→点右上暂停并确认PAUSE→安排每条敌方通路→点底部干员卡查看绿色可部署格→从该卡内部拖到明确的一格松手→看到角色和白色菱形后，从角色中心向所选方向拖动松手→核对部署登记→恢复1倍战斗并处理敌人/技能/装置→看到本次胜利结算才完成。最后的朝向操作不改变落点；部署两人、没有剩余卡、费用下降都不是通关。

B看不到前文。每次target重新说明本次卡片/角色、源位置、唯一地块的通路和地标关系、手指起点→终点及松手；expected说明下一张图应该出现什么。object_drag用于卡片→地块，physical_gesture用于角色中心→朝向箭头。右下卡拖到中间格可以向左上，不能把“装置上方”写成手指竖直向上，也不能小幅拖到够不到目标。

不预设账户队伍或固定通关坐标。操作以当前截图为准；任务要求只进入关卡时不启动战斗。
