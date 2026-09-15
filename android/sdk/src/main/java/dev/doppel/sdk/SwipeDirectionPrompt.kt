package dev.doppel.sdk

/** Shared language convention; the executor always follows the supplied path unchanged. */
internal object SwipeDirectionPrompt {
    const val SEMANTICS = """滑动方向规则（内容移动与手指同向，露出的内容来自相反一侧）：
浏览目标 → 实际手指路径
看下方内容 / 向下浏览 / 向下滑页面 → 下到上。
看上方内容 / 向上浏览 → 上到下。
露出左侧内容 / 找左边更早的条目 → 左到右，手指右滑。
露出右侧内容 → 右到左，手指左滑。
明确要求的物理手势 → 实际手指路径
向左滑动 → 右到左；向右滑动 → 左到右。
手指向下拖 / 下拉通知栏 / 下拉刷新 → 上到下，不反转。明确手指方向和朝向选择使用physical_gesture；对象从起点拖到指定落点使用object_drag，target同时描述源对象与目标落点，按两者实际位置执行。
先确定是在描述“想看哪边的内容”还是“手指往哪边走”，再使用上述路径。寻找更早条目先结合可见顺序判断在哪边；目标在左侧不等于手指左滑。只有实际观察支持特殊交互时才调整规则，不凭空假定轮播或游戏反向。
target 说明可见操作区域和手指起止方向；expected 说明希望露出的内容或变化。两者必须一致。旧 last_intent 和旧路径只是先前尝试，不能代替本轮方向判断。连续滑动逐段遵循同一约定。
浏览时target_relative_direction指“希望找到的内容相对当前可见内容在哪一侧”，不是可拖动背景区域的位置，也不是手指运动方向。object_drag时该字段指目标落点相对源对象的方位，与实际手指方向同向；无法准确估计可为unknown，由B按同一源对象和落点细化并记录修正。intended_finger_direction / required_finger_direction均指手指从轨迹起点到终点的实际运动。先依据截图确定对象及方位，再明确手指运动，最后据此生成轨迹；expected的描述也必须一致。
"""

    const val COORDINATES = """路径 points 按时间排列为起点→终点；0..1000 坐标中 x 向右增大，y 向下增大。
输出前核对：看下方内容须终点 y < 起点 y；看上方内容须终点 y > 起点 y；看左侧内容/手指右滑须终点 x > 起点 x；看右侧内容/手指左滑须终点 x < 起点 x。
实际起止点必须根据当前截图选择可拖动区域，不固定套用居中大幅轨迹。滑动幅度与方向是不同的问题，正确方向的大幅滑动也可能越过目标。
浏览默认小幅：每次 swipe/swipe_sequence 明确 swipe_extent=small，并在 target 写“小幅滑动”，起终点沿移动轴相距约屏幕该轴的 5%–15%，接近目标可更短。慢拖后松手，避免短促甩动；距离短仍可能因惯性移动很远，必须根据实际结果缩短并减速。只有明确想直达页面末端/边界时才 large=30%–60%，同时声明 scroll_goal=boundary 和 boundary_reason；普通找目标用 scroll_goal=inspect，必须 small，不借“尚未找到”默认扫到末端。连续滑动每一段都遵循幅度要求。物理拖放、下拉系统面板等按真实起点和目标位置，不能套用浏览反向或默认距离。
"""

    const val GROUNDING_CORRECTION = """独立方向复核：A 的方向字段和 expected 的方向用词可能有误。先结合当前截图判断 A 要找的同一内容在哪一侧，再确定手指路径。要显示屏幕右边的内容，应从右向左拖；要显示左边，应从左向右拖；要显示下方，应从下向上；要显示上方，应从上向下。内容本身跟手指同向移动，“露出右侧内容”和“让当前内容向右移动”含义相反。
若能从当前截图和目标确定只是浏览方向写反了，保持同一任务、操作区域和目标，自行修正 target_relative_direction/required_finger_direction，direction_corrected=true，然后输出符合修正后方向的坐标执行；不要因这一可纠正方向矛盾直接拒绝。没有方向修正则 direction_corrected=false。每段单独声明。不要输出修正理由或描述段落，宿主会记录前后方向供 A 查看。目标身份或页面关系本身无法确定才返回 ambiguous/intent_mismatch；不得为了自圆其说更换 A 要找的目标。physical_gesture是明确手指方向或朝向选择，不得改写其方向；object_drag按同一源对象和落点细化几何，目标方位与手指方向同向，任一方向变化（含unknown具体化）必须direction_corrected=true，不能套用浏览反向。
"""

    const val RECOVERY = """
先辨认页面结构；不确定是否可滑时可做一次小幅探查，对比同一地标是否移动。多次无预期变化后重新判断区域、边界或入口，不能只换方向重试。recent_swipe_motion 是实际执行路径，未执行过的方向不能记为失败。普通浏览仍须 small；越过目标时更短、更慢地回滑，small 标签不保证内容位移小。
swipe_sequence 每段后截图、整组后交给你。按顺序对比中间图和最后当前图；目标曾出现而后消失时重新定位，不能点击历史位置。连续执行仅适用于当前已知的同一交互流程。"""
}
