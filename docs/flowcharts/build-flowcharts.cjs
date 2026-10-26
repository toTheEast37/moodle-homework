/* Rebuild the documentation with Node.js. Uses the bundled @viz-js/viz and marked. */
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const bundledRequire = process.env.FLOWCHART_NODE_MODULES
  ? createRequire(path.join(process.env.FLOWCHART_NODE_MODULES, '__flowcharts__.cjs'))
  : require;
const { instance } = bundledRequire('@viz-js/viz');
const { marked } = bundledRequire('marked');
const root = __dirname;
const graphs = [];
const g = (id, title, direction, nodes, edges) => graphs.push({ id, title, direction, nodes, edges });

g('01-architecture', '系统架构与职责', 'TB', [
 ['ui','工作台网页\nHTML / CSS / app.js'], ['api','Controller + 登录拦截\n会话、账号、状态、启动'],
 ['svc','Service\n凭据管理 / CollectorCoordinator'], ['repo','Repository / JdbcTemplate\n带 owner_user_id 的 SQL'],
 ['db','MySQL\n用户 / 密钥 / 凭据 / 运行快照','db'], ['worker','每用户后台守护线程\n进度回调 / 单轮采集'],
 ['pw','Playwright → 独立 Edge\n临时 BrowserContext'], ['moodle','Moodle + 学校 SSO\n课程目录 / 作业活动汇总'],
 ['scholat','学者网\n我的课程 / 作业动态'], ['files','output/ 历史 JSON\n每轮目录 / 每门课程文件','db']
], [['ui','api','同源 HTTP / Session Cookie'],['api','svc','身份和参数'],['svc','repo','读写'],['repo','db','SQL'],
 ['svc','worker','启动后立即返回状态'],['worker','pw','浏览器自动化'],['pw','moodle','登录 / 读取 DOM'],['pw','scholat','登录 / 读取 DOM'],
 ['worker','svc','Progress 回调','dashed'],['worker','files','导出'],['svc','api','ApiSnapshot','dashed'],['api','ui','前端每秒轮询','dashed']]);

g('02-overall', '从打开工作台到看到待办', 'TB', [
 ['a','打开本机工作台\n127.0.0.1:8080','start'],['b','GET /api/session'],['c','工作台会话有效？','decision'],
 ['d','输入工作台账号密码\nPOST /api/login'],['e','加载平台账号 + 历史快照'],['f','选择 Moodle / 学者网\n绑定账号或选择 Moodle 手动登录'],
 ['h','POST /api/start\n校验会话 / Origin / 账号归属'],['i','当前用户已有采集？','decision'],['j','409：拒绝重复启动','error'],
 ['k','创建运行记录\n后台线程启动 Playwright'],['l','平台登录 → 读取课程 → 读取作业'],['m','进度回调更新内存和数据库\n课程文件导出到 output/'],
 ['n','整轮终态','decision'],['o','completed / partial\n保存本轮有效快照'],['p','error\n保留该来源旧有效快照','error'],
 ['q','每秒 GET /api/status\n合并各来源 → 去重 → 截止时间筛选'],['r','展示总待办 / 课程 / 平台\n列表不判断是否已提交','end']
], [['a','b'],['b','c'],['c','d','否'],['d','e','登录成功'],['c','e','是'],['e','f'],['f','h'],['h','i'],['i','j','是'],['i','k','否'],['k','l'],['l','m'],['m','n'],['n','o','完成或部分完成'],['n','p','整轮失败'],['o','q'],['p','q'],['q','r']]);

g('03-session', '工作台登录与会话', 'TB', [
 ['a','POST /api/login\n工作台 username / password','start'],['b','Origin 与请求协议 / 主机 / 端口一致？','decision'],
 ['x','403 拒绝','error'],['c','必要字段存在？','decision'],['y','400 参数错误','error'],['d','查询 enabled=true 的 app_user'],
 ['e','找到用户且 PBKDF2 校验一致？','decision'],['z','401：账号或密码不正确','error'],['f','创建或取得 HttpSession\nchangeSessionId()'],
 ['h','保存 AuthSession(userId, username)'],['i','浏览器携带会话 Cookie 请求受保护接口'],['j','拦截器检查 Session 中 AuthSession','decision'],
 ['k','进入 Controller\n后续按 userId 隔离数据','end'],['l','POST /api/logout\n校验 Origin → invalidate Session','end']
], [['a','b'],['b','x','否'],['b','c','是'],['c','y','否'],['c','d','是'],['d','e'],['e','z','否'],['e','f','是'],['f','h'],['h','i'],['i','j'],['j','z','无会话'],['j','k','有会话'],['k','l','用户退出']]);

g('04-credentials', '平台凭据的保存与使用', 'TB', [
 ['a','工作台用户新增 / 编辑平台账号','start'],['b','校验平台、长度、账号归属\n新增必须填密码 / 已有账号不可换平台'],
 ['c','本次填写了新密码？','decision'],['d','编辑时留空\n只更新备注和用户名'],['e','按 owner_user_id 获取或创建\n32 字节随机 AES 密钥'],
 ['f','产生 12 字节随机 nonce'],['h','AES-256-GCM 加密\nAAD = 用户编号 + 平台编码'],['i','数据库保存 nonce + ciphertext\n密钥另存在 extractor_credential_key','db'],
 ['j','返回账号摘要\n不返回密码、密文和密钥','end'],['k','启动自动登录\n按 owner + credentialId 查询'],
 ['l','取该工作台用户密钥\n同一 AAD 解密得到 char[]'],['m','交给平台登录表单\nfinally 清理可控数组','end']
], [['a','b'],['b','c'],['c','d','否：仅编辑'],['d','j'],['c','e','是'],['e','f'],['f','h'],['h','i'],['i','j'],['i','k','之后采集'],['k','l'],['l','m']]);

g('05-dispatch', '采集协调与并发控制', 'TB', [
 ['a','CollectorCoordinator.start(user, id, mode)','start'],['b','取得 states[userId]\n同一用户共用 UserState'],['c','CAS：running 从 false 改为 true？','decision'],
 ['d','409：已有一轮运行','error'],['e','检查凭据属于当前用户\n验证 auto / manual 与平台支持'],['f','参数或解密失败？','decision'],
 ['x','running=false\n关闭已取得的明文凭据并抛错','error'],['h','创建 site_collection_run\n初始 opening_browser'],['i','保存 activeCredentialId / 初始进度'],
 ['j','启动 daemon 后台线程\nHTTP 返回当前状态'],['k','siteCode == scholat？','decision'],['l','ScholatHomework.run\n绑定账号自动登录'],
 ['m','MoodleHomework.run\n有凭据自动 / 无凭据手动'],['n','Progress 回调\nAtomicReference.set → 更新数据库'],['o','异常由协调器登记\n已有 partial 则保留，否则 error'],
 ['p','finally：清空密码 char[]\nrunning=false，允许下一轮','end']
], [['a','b'],['b','c'],['c','d','否'],['c','e','是'],['e','f'],['f','x','是'],['f','h','否'],['h','i'],['i','j'],['j','k','后台执行'],['k','l','是'],['k','m','否'],['l','n','每次回调'],['m','n','每次回调'],['n','o','发生异常'],['n','p','正常完成'],['o','p']]);

g('06-moodle-login', 'Moodle 登录与学校统一认证', 'TB', [
 ['a','启动有界面 Edge\n新 BrowserContext / zh-CN / 上海时区','start'],['b','访问 /my/index.php'],['c','检测到 Moodle 已登录页面标记？','decision'],
 ['d','有自动登录凭据？','decision'],['e','waiting_login\n手动登录最多等待 300 秒'],['f','访问 /login/index.php\n点击 #ssobtn'],
 ['h','等待进入 sso.scnu.edu.cn'],['i','当前为账号密码登录页？','decision'],['j','在主页面和 iframe 中寻找\n唯一可见账号框 + 密码框'],
 ['k','填账号密码 → 唯一登录按钮\n等待 Moodle 或 SSO 授权页'],['l','当前为 SSO 授权页？','decision'],
 ['m','点击首个可见 gotoApp 确认链接'],['n','自动模式等待 Moodle 登录标记\n最多 60 秒'],['o','进入完整课程目录读取','end'],['x','超时 / 表单不唯一 / 页面变化\n抛错，由协调器登记 error','error']
], [['a','b'],['b','c'],['c','o','是'],['c','d','否'],['d','e','否'],['e','o','登录标记出现'],['d','f','是'],['f','h'],['h','i'],['i','j','是'],['j','k'],['k','l'],['i','l','否'],['l','m','是'],['m','n'],['l','n','否'],['n','o','标记出现'],['j','x','定位失败'],['e','x','超时'],['n','x','超时']]);

g('07-catalog', 'Moodle 课程目录完整读取', 'TB', [
 ['a','CourseCatalog.findCourses\n访问个人课程页并校验 HTTP','start'],['b','等待当前页可见且有课程 / 空标志'],['c','记录原分组 / 排序 / 显示方式'],
 ['d','改为 fullname 稳定排序 + card 显示'],['e','依次处理 all 和 hidden 分组'],['f','切换分组后等待新列表容器\n返回第 1 页'],['h','页码已访问或超过 1000 页？','decision'],
 ['x','停止并抛错\n不能宣称目录读取完整','error'],['i','只解析 active-page-number 对应容器'],['j','校验课程 ID / 链接 / 标题\nLinkedHashMap 按 courseId 去重'],
 ['k','下一页控件存在且唯一？','decision'],['l','下一页已禁用？','decision'],['m','点击下一页\n等待页码改变 + 当前内容就绪'],['n','还有另一分组？','decision'],
 ['o','返回 Catalog(courses, pagesRead)'],['p','finally 尝试恢复原显示偏好\n恢复失败仅记录提示','end']
], [['a','b'],['b','c'],['c','d'],['d','e'],['e','f'],['f','h'],['h','x','是'],['h','i','否'],['i','j'],['j','k'],['k','x','否'],['k','l','是'],['l','m','否'],['m','h'],['l','n','是'],['n','f','是'],['n','o','否'],['o','p'],['x','p','退出前也尝试恢复']]);

g('08-moodle-assignments', 'Moodle 单门课程作业读取', 'TB', [
 ['a','AssignmentCollector.collect(page, course)','start'],['b','访问 /course/overview.php\n携带课程 id 与 assign 展开参数'],['c','校验 HTTP + stateready\n域名 / 路径 / URL 课程编号'],
 ['d','存在 #assign_overview_collapsible？','decision'],['e','返回空列表\n课程结果 empty'],['f','未展开则点击展开\n等待表格出现 + loading 隐藏'],['h','出现未知分页结构？','decision'],
 ['x','抛出解析 / 访问异常\n该门课程 error，继续下一门','error'],['i','校验名称列与作业行结构'],['j','逐行读取 cmid / 标题 / href'],['k','编号与 /mod/assign/view.php 链接一致？','decision'],
 ['l','读取 deadlineRaw\n有正秒级时间戳则转上海时区'],['m','按作业 ID 去重\n返回 Assignment 列表'],['n','生成 ok 或 empty 的 CourseResult'],
 ['o','写 course-ID.json → 加入 results\n更新 summary.json → 发布进度','end']
], [['a','b'],['b','c'],['c','d','通过'],['c','x','失败'],['d','e','否'],['e','n'],['d','f','是'],['f','h'],['h','x','是'],['h','i','否'],['i','j','结构正确'],['i','x','结构变化'],['j','k'],['k','x','否'],['k','l','是'],['l','m','所有行读完'],['l','x','时间戳非法'],['m','n'],['n','o'],['x','o','生成 error 的 CourseResult']]);

g('09-scholat', '学者网登录与作业动态解析', 'TB', [
 ['a','新 Edge Context\n进入 scholat 登录页面','start'],['b','唯一可见账号框 / 密码框 / 提交按钮'],['c','检测到登录前验证码？','decision'],
 ['x','抛错 → 本轮 error\n本轮不提交新有效快照','error'],['d','填绑定账号密码 → 提交'],['e','扫描同 Context 中学者网标签页\n新标签优先，登录表单必须消失'],
 ['f','找到我的课程 / 作业动态入口\n或可见作业表格'],['h','必要时点击“暂不认证”\n按真实链接进入我的课程'],['i','必要时点击作业动态\n等待可见 homeworkList 表格'],
 ['j','表格块数量 > 0？','decision'],['k','遍历可见课程表格块'],['l','读取紧邻的前一个标题 div\n校验课程标题 / 链接 / courseId'],
 ['m','逐行跳过表头 / 显式暂无作业\n校验列数和 homeworkId 链接'],['n','读取第 3 列截止日期\n只规范化为 yyyy-MM-dd'],['o','写课程 JSON\n发布 collecting 进度'],
 ['p','全部课程读完\n写 summary.json → completed','end']
], [['a','b'],['b','c'],['c','x','是'],['c','d','否'],['d','e'],['e','f','登录证据成立'],['e','x','60 秒超时'],['f','h'],['h','i'],['i','j'],['j','x','否 / 超时'],['j','k','是'],['k','l'],['l','m'],['m','n'],['n','o'],['o','k','还有课程'],['o','p','全部完成'],['l','x','结构错误'],['m','x','缺列 / 缺链接'],['n','x','日期无法识别']]);

g('10-states', '进度阶段与终态', 'LR', [
 ['a','idle\n仅内存初始状态','start'],['b','opening_browser'],['c','waiting_login\n若已登录可跳过'],['d','reading_courses'],['e','collecting\n可多次回调'],
 ['f','completed\n写有效快照','end'],['h','partial\nMoodle 有失败课程\n写有效快照','end'],['i','error\n整轮失败\n通常不产生新有效快照','error']
], [['a','b','有效启动'],['b','c','需要登录'],['b','d','已登录'],['c','d','登录成功'],['d','e','目录就绪'],['e','e','逐课程'],['e','f','全部成功'],['e','h','Moodle 失败课程 > 0'],['b','i','异常'],['c','i','异常'],['d','i','异常'],['e','i','整轮异常']]);

g('11-persistence', '进度、快照和历史文件', 'TB', [
 ['a','采集器产生 Progress','start'],['b','回调更新内存 progress\n然后 UPDATE site_collection_run'],['c','写 status / progress_json\n按 runId + owner_user_id 限定'],
 ['d','stage 为 completed / partial？','decision'],['e','result_json = 本轮 Progress\nfinished_at = 当前时间'],['f','stage 为 error？','decision'],
 ['h','记录错误和 finished_at\n本次不写新 result_json'],['i','运行中：只更新进度'],['j','状态查询：只选 result_json 非空的记录'],
 ['k','按用户 + 平台 + 账号分组\n取 finished_at 最大，平局取 id 最大'],['l','返回各账号最近有效 ResultSnapshot','end'],
 ['m','独立文件输出：完整 JSON → .tmp\n优先 ATOMIC_MOVE 替换目标','db']
], [['a','b'],['b','c'],['c','d'],['d','e','是'],['d','f','否'],['f','h','是'],['f','i','否'],['e','j'],['h','j'],['i','j'],['j','k'],['k','l'],['a','m','采集器另行写文件；不与 SQL 共享事务','dashed']]);

g('12-dashboard', '看板的轮询、合并与筛选', 'TB', [
 ['a','showApp\n加载凭据 → 立即 refresh → 每秒定时','start'],['b','polling 为 true？','decision'],['c','本次跳过，避免请求重叠','end'],['d','保存 sessionGeneration\nGET /api/status?credentialId=...'],
 ['e','响应仍属于当前会话代次？','decision'],['f','丢弃旧会话迟到响应','end'],['h','401？','decision'],['i','showLogin：清空数据 / 密码框\n停止定时器','end'],
 ['j','读取 snapshot.results\n每个来源一份有效快照'],['k','Map 合并：平台 + 凭据 + 课程 ID'],['l','若 running，再用本轮已读课程\n覆盖相同课程键'],
 ['m','展开作业\n按课程复合键 + 作业 ID 去重'],['n','计算截止时间桶 + 排序'],['o','平台 AND 关键词 AND 截止条件'],
 ['p','渲染总待办 / 课程 / 平台状态\n缓存渲染键，减少重复 DOM 更新','end']
], [['a','b'],['b','c','是'],['b','d','否'],['d','e'],['e','f','否'],['e','h','是'],['h','i','是'],['h','j','否且请求成功'],['j','k'],['k','l'],['l','m'],['m','n'],['n','o'],['o','p']]);

g('13-deadline', '截止时间的两种精度', 'TB', [
 ['a','assignment.deadline','start'],['b','严格匹配 yyyy-MM-dd？','decision'],['c','学者网日期型\n按上海今天的年月日比较'],['d','days = 截止日期 − 今天\n仅比较日历日期'],
 ['e','Date.parse 可得到有效时刻？','decision'],['f','Moodle 时刻型\nremainingMs = dueMs − nowMs'],['h','none：时间待确认\n不猜年份或截止时刻'],
 ['i','overdue：已过期\n日期型 days < 0 / 时刻型剩余 < 0'],['j','week：未过期且 ≤ 7 天'],['k','fortnight：> 7 且 ≤ 14 天'],['l','later：> 14 天'],
 ['m','日期型今天仍是“今天截止”\n默认 upcoming = 除 overdue 外全部','end']
], [['a','b'],['b','c','是'],['c','d'],['b','e','否'],['e','f','是'],['e','h','否'],['d','i','< 0 天'],['d','j','0～7 天'],['d','k','8～14 天'],['d','l','> 14 天'],['f','i','< 0'],['f','j','≤ 7 天且未过期'],['f','k','> 7 且 ≤ 14 天'],['f','l','> 14 天'],['i','m'],['j','m'],['k','m'],['l','m'],['h','m']]);

g('14-failures', '不同失败位置的结果', 'TB', [
 ['a','本轮采集出现问题','start'],['b','失败发生在哪一层？','decision'],['c','启动校验 / 权限 / 解密失败'],['d','登录 / Moodle 目录失败'],
 ['e','Moodle 单课程访问或解析失败'],['f','学者网单课程解析失败'],['h','SQL / 文件写入失败'],['i','无后台任务或提前终止\n返回相应 HTTP 错误'],
 ['j','协调器登记 error\n本轮通常无 result_json'],['k','该课程 error，继续其他课程\n最终 partial，写本轮有效快照'],
 ['l','已读文件可能仍留在 output/\n不能等同本轮数据库有效快照'],['m','旧有效结果由数据库查询保留\npartial 则整体成为该来源新快照','end']
], [['a','b'],['b','c','启动前'],['b','d','登录 / 目录'],['b','e','Moodle 课程'],['b','f','学者网课程'],['b','h','持久化'],['c','i'],['d','j'],['e','k'],['f','j'],['h','j','异常可抛回协调器'],['j','l'],['k','l'],['l','m']]);

g('15-legacy', '旧版 Moodle 文件的谨慎导入', 'TB', [
 ['a','GET /api/status\n所选来源没有数据库有效结果','start'],['b','来源是 Moodle？','decision'],['c','账号归属可以无歧义判断？','decision'],
 ['x','不导入，照常返回现有状态','end'],['d','扫描 output/user-ID/run-*\n按 summary 修改时间倒序'],['e','summary 为 completed / partial？','decision'],
 ['f','读课程文件\n校验规范化路径位于运行目录内'],['h','至少读到一门课程？','decision'],['i','再次检查数据库没有有效结果\n插入 run_mode=legacy 快照'],['j','重新查询数据库 → 返回状态','end']
], [['a','b'],['b','x','否'],['b','c','是'],['c','x','否'],['c','d','是'],['d','e'],['e','x','没有可用轮次'],['e','f','是'],['f','h'],['h','x','否'],['h','i','是'],['i','j']]);

g('16-data-model', '数据表关系与 JSON 聚合结构', 'LR', [
 ['u','app_user\nPK id / UNIQUE username\npassword_hash / enabled','db'],
 ['key','extractor_credential_key\nPK + FK owner_user_id\nencryption_key BINARY(32)','db'],
 ['cred','site_login_credential\nPK id / FK owner_user_id\nsite_code / login_username\nnonce / ciphertext','db'],
 ['run','site_collection_run\nPK id / FK owner_user_id\nnullable FK site_credential_id\nstatus / progress_json / result_json','db'],
 ['progress','Progress JSON\nstage / counts / currentCourse\noutputDirectory / courses[]'],
 ['course','CourseResult\ncourseId / courseName / status\nassignmentCount / error / assignments[]'],
 ['assignment','Assignment\nid / title / url\ndeadlineRaw / deadline']
], [['u','key','1 → 0..1'],['u','cred','1 → 0..N'],['u','run','1 → 0..N'],['cred','run','1 → 0..N；运行可不绑定凭据'],['run','progress','JSON 列序列化'],['progress','course','包含 0..N'],['course','assignment','包含 0..N']]);

const esc = value => String(value).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
const quote = value => JSON.stringify(value);
function toMermaid(graph) {
  const shape = (id, label, type) => {
    const q = quote(label.replaceAll('\n','<br/>'));
    if (type === 'decision') return `  ${id}{${q}}`;
    if (type === 'db') return `  ${id}[(${q})]`;
    if (type === 'start' || type === 'end') return `  ${id}([${q}])`;
    return `  ${id}[${q}]`;
  };
  return [`flowchart ${graph.direction}`, ...graph.nodes.map(n => shape(...n)), ...graph.edges.map(([a,b,label,style]) =>
    `  ${a} ${style === 'dashed' ? '-.->' : '-->'}${label ? `|${quote(label)}|` : ''} ${b}`)].join('\n');
}
function toDot(graph) {
  const shapes = { decision:'diamond', db:'cylinder', start:'oval', end:'oval', error:'box' };
  const nodes = graph.nodes.map(([id,label,type]) => {
    const longest = Math.max(...label.split('\n').map(line => [...line].reduce((n,c) => n + (c.codePointAt(0) > 255 ? 1 : .57), 0)));
    const width = Math.max(2.35, longest * 13 / 72 + .46);
    const attrs = { label, shape: shapes[type] || 'box', width: String(width), margin: '.18,.14', fillcolor: type === 'error' ? '#fff1ef' : type === 'decision' ? '#fff7df' : type === 'db' ? '#eaf1ff' : type === 'end' ? '#e7f5f0' : '#f7fafc', color:type === 'error' ? '#c37e72' : '#a9b8c7' };
    attrs.label = label.replaceAll('\n','\n\n');
    return `${id} [${Object.entries(attrs).map(([k,v]) => `${k}=${quote(v)}`).join(',')}];`;
  });
  const edges = graph.edges.map(([a,b,label,style]) => {
    const width = Math.ceil([...label || ''].reduce((sum,c) => sum + (c.codePointAt(0)>255 ? 11 : 6.3),0) + 12);
    const edgeLabel = label ? `<<TABLE BORDER="0" CELLBORDER="0" CELLSPACING="0" CELLPADDING="2"><TR><TD WIDTH="${width}">${esc(label)}</TD></TR></TABLE>>` : '""';
    return `${a} -> ${b} [label=${edgeLabel}${style ? ',style=dashed' : ''}];`;
  });
  return `digraph G { graph [rankdir=${graph.direction === 'TB' ? 'TB' : 'LR'},bgcolor="transparent",pad="0.25",nodesep="0.38",ranksep="0.42",fontname="Microsoft YaHei",outputorder="edgesfirst"]; node [fontname="Microsoft YaHei",fontsize=13,style="rounded,filled",fontcolor="#172d42",penwidth=1]; edge [fontname="Microsoft YaHei",fontsize=11,color="#708397",fontcolor="#4b6278",arrowsize=.65,penwidth=1.1]; ${nodes.join('\n')} ${edges.join('\n')} }`;
}

async function main() {
  fs.mkdirSync(path.join(root,'diagrams'), { recursive:true });
  const viz = await instance();
  const svgMap = new Map();
  for (const graph of graphs) {
    const svg = viz.renderString(toDot(graph), { format:'svg' });
    const viewWidth = Number(/viewBox="[^"]*?\s([\d.]+)\s[\d.]+"/.exec(svg)?.[1]);
    if (!Number.isFinite(viewWidth)) throw new Error('Missing SVG viewBox: '+graph.id);
    svgMap.set(graph.id, svg.slice(svg.indexOf('<svg')).replace('<svg ', `<svg role="img" aria-label="${esc(graph.title)}" style="min-width:${Math.ceil(viewWidth)}px" `));
    fs.writeFileSync(path.join(root,'diagrams',graph.id+'.svg'), svg);
    fs.writeFileSync(path.join(root,'diagrams',graph.id+'.mmd'), toMermaid(graph)+'\n');
  }
  const source = fs.readFileSync(path.join(root,'explanation.source.md'),'utf8');
  const expanded = source.replace(/<!-- diagram:([\w-]+) -->/g, (_,id) => {
    const graph = graphs.find(g => g.id === id);
    if (!graph) throw new Error('Unknown diagram '+id);
    return `\n![${graph.title}](diagrams/${id}.svg)\n\n<details>\n<summary>展开可编辑的 Mermaid 源码</summary>\n\n\`\`\`mermaid\n${toMermaid(graph)}\n\`\`\`\n\n</details>\n`;
  });
  fs.writeFileSync(path.join(root,'project-flowcharts.md'), expanded);
  let htmlSource = source.replace(/<!-- diagram:([\w-]+) -->/g, (_,id) => `<div class="diagram" data-diagram="${id}"><div class="diagram-toolbar"><span>流程图 · 可横向滚动查看</span><button type="button" class="expand-diagram" data-id="${id}">放大查看</button></div><div class="diagram-scroll">${svgMap.get(id)}</div></div>\n`);
  const toc = [];
  const renderer = new marked.Renderer();
  renderer.heading = ({tokens,depth}) => {
    const text = renderer.parser.parseInline(tokens);
    const id = 'section-'+toc.length;
    if (depth === 2) toc.push({id,text});
    return `<h${depth}${depth === 2 ? ` id="${id}"` : ''}>${text}</h${depth}>`;
  };
  const body = marked.parse(htmlSource, { renderer });
  const nav = toc.map(x => `<a href="#${x.id}">${x.text}</a>`).join('');
  const css = fs.readFileSync(path.join(root,'document.css'),'utf8');
  const js = fs.readFileSync(path.join(root,'document.js'),'utf8');
  fs.writeFileSync(path.join(root,'project-flowcharts.html'), `<!doctype html>\n<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Moodle 作业工作台 · 流程图与原理详解</title><style>${css}</style></head><body><aside class="sidebar"><div class="brand">PROJECT ATLAS<span>作业工作台 · 代码导读</span></div><nav aria-label="文档目录">${nav}</nav><div class="sidebar-foot">依据当前源码 · 2026-10-26<br>16 张流程图 · 离线可读</div></aside><main><div class="topline"><span>JAVA 17 / SPRING BOOT / PLAYWRIGHT</span><button type="button" id="print">打印 / 保存 PDF</button></div><article>${body}</article><footer>流程图描述当前实现；文中的改进建议不代表项目已经具备相应能力。</footer></main><dialog id="diagram-dialog"><header><span id="dialog-title">流程图</span><button type="button" id="zoom-out" aria-label="缩小">−</button><button type="button" id="zoom-in" aria-label="放大">＋</button><button type="button" id="dialog-close">关闭</button></header><div id="dialog-content"></div></dialog><script>${js}</script></body></html>`);
  fs.writeFileSync(path.join(root,'diagrams.data.json'), JSON.stringify(graphs,null,2)+'\n');
  console.log(JSON.stringify({diagrams:graphs.length,sections:toc.length,markdownBytes:Buffer.byteLength(expanded),htmlBytes:fs.statSync(path.join(root,'project-flowcharts.html')).size}));
}
main().catch(e => { console.error(e); process.exitCode=1; });
