const byId = (id) => document.getElementById(id);
let selectedCourseId = null;
let polling = false;
let renderedCoursesKey = null;
let renderedSelectedCourseId = null;
let renderedUpcomingKey = null;
let currentNowMs = Date.now();
let statusTimer = null;
let credentials = [];
let dashboardCourses = [];
let dashboardResults = [];
let todoSite = 'all';
let renderedTodoKey = null;
let renderedPlatformsKey = null;
let sessionGeneration = 0;
const siteNames = { 'scnu-moodle': '砺儒云课堂', scholat: '学者网' };
const DAY_MS = 24 * 60 * 60 * 1000;
const shanghaiDateTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
});
const shanghaiDate = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'
});

const stageLabels = {
  idle: '未开始',
  opening_browser: '正在启动',
  waiting_login: '正在登录',
  reading_courses: '读取课程',
  collecting: '采集作业',
  completed: '已完成',
  partial: '部分完成',
  error: '需要处理'
};

function progressPercent(progress) {
  if (progress.stage === 'completed') return 100;
  if (progress.stage === 'partial' || progress.stage === 'error') {
    return progress.totalCourses > 0
      ? Math.min(98, Math.round(progress.processedCourses * 100 / progress.totalCourses))
      : 0;
  }
  if (progress.stage === 'opening_browser') return 8;
  if (progress.stage === 'waiting_login') return 14;
  if (progress.stage === 'reading_courses') return 24;
  if (progress.stage === 'collecting' && progress.totalCourses > 0) {
    return Math.min(96, 25 + Math.round(progress.processedCourses * 70 / progress.totalCourses));
  }
  return 0;
}

function deadlineInfo(deadline, nowMs) {
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(deadline || '');
  if (dateOnly) {
    const today = Object.fromEntries(shanghaiDate.formatToParts(new Date(nowMs))
      .filter(part => ['year', 'month', 'day'].includes(part.type))
      .map(part => [part.type, Number(part.value)]));
    const dueMs = Date.UTC(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3]));
    const todayMs = Date.UTC(today.year, today.month - 1, today.day);
    const days = Math.round((dueMs - todayMs) / DAY_MS);
    const bucket = days < 0 ? 'overdue' : days <= 7 ? 'week' : days <= 14 ? 'fortnight' : 'later';
    return { bucket, dueMs, days, dateOnly: true };
  }
  const dueMs = Date.parse(deadline || '');
  if (!Number.isFinite(dueMs)) return { bucket: 'none', dueMs: NaN, dateOnly: false };
  const remainingMs = dueMs - nowMs;
  const bucket = remainingMs < 0 ? 'overdue' : remainingMs <= DAY_MS * 7
    ? 'week' : remainingMs <= DAY_MS * 14 ? 'fortnight' : 'later';
  return { bucket, dueMs, dateOnly: false };
}

function deadlineBucket(deadline, nowMs) {
  return deadlineInfo(deadline, nowMs).bucket;
}

function remainingLabel(dueMs, nowMs) {
  const remainingMs = Math.max(0, dueMs - nowMs);
  const days = Math.floor(remainingMs / DAY_MS);
  const hours = Math.floor((remainingMs % DAY_MS) / (60 * 60 * 1000));
  if (days > 0) return hours > 0 ? `剩 ${days} 天 ${hours} 小时` : `剩 ${days} 天`;
  if (hours > 0) return `剩 ${hours} 小时`;
  const minutes = Math.max(1, Math.ceil(remainingMs / (60 * 1000)));
  return `剩 ${minutes} 分钟`;
}

function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}

function formatDeadline(deadline) {
  if (!deadline) return '未设置截止时间';
  if (/^\d{4}-\d{2}-\d{2}$/.test(deadline)) return deadline;
  const parsed = Date.parse(deadline);
  return Number.isFinite(parsed) ? shanghaiDateTime.format(new Date(parsed)) : '截止时间无法识别';
}

function courseIdentity(siteCode, credentialId, course) {
  return JSON.stringify([siteCode, credentialId ?? 'manual', course.courseId ?? course.courseUrl]);
}

function combineCourses(results, snapshot) {
  const courses = new Map();
  const add = (progress, source) => {
    for (const course of progress?.courses || []) {
      const courseKey = courseIdentity(source.siteCode, source.siteCredentialId, course);
      courses.set(courseKey, { ...course, courseKey, siteCode: source.siteCode,
        credentialId: source.siteCredentialId, accountName: source.displayName });
    }
  };
  for (const result of results) add(result.progress, result);
  // During collection, keep saved courses until each has actually been read again.
  if (snapshot.running) {
    const account = credentials.find(item => item.id === snapshot.activeCredentialId);
    add(snapshot.progress, {
      siteCode: account?.siteCode || 'scnu-moodle', siteCredentialId: snapshot.activeCredentialId,
      displayName: account?.displayName || '手动登录'
    });
  }
  return [...courses.values()];
}

function allAssignments(courses, nowMs) {
  const entries = [];
  const seen = new Set();
  for (const course of courses) {
    for (const assignment of course.assignments || []) {
      const key = JSON.stringify([course.courseKey, assignment.id ?? assignment.url ?? assignment.title]);
      if (seen.has(key)) continue;
      seen.add(key);
      entries.push({ ...assignment, courseName: course.courseName, siteCode: course.siteCode,
        accountName: course.accountName, info: deadlineInfo(assignment.deadline, nowMs) });
    }
  }
  entries.sort((a, b) => (Number.isFinite(a.info.dueMs) ? a.info.dueMs : Infinity)
    - (Number.isFinite(b.info.dueMs) ? b.info.dueMs : Infinity) || a.title.localeCompare(b.title, 'zh-CN'));
  return entries;
}

function openAccounts() {
  byId('accounts').open = true;
}

function renderTodo() {
  const search = byId('todoSearch').value.trim().toLocaleLowerCase();
  const filter = byId('todoDeadlineFilter').value;
  const all = allAssignments(dashboardCourses, currentNowMs);
  const entries = all.filter(item => (todoSite === 'all' || item.siteCode === todoSite)
    && (filter === 'all' || (filter === 'upcoming' ? item.info.bucket !== 'overdue' : item.info.bucket === filter))
    && (!search || [item.title, item.courseName, item.accountName, siteNames[item.siteCode]]
      .join(' ').toLocaleLowerCase().includes(search)));
  const key = JSON.stringify([entries, filter, todoSite, search, Math.floor(currentNowMs / 60000), dashboardResults.length]);
  if (key === renderedTodoKey) return;
  renderedTodoKey = key;
  byId('navTodoCount').textContent = all.filter(item => item.info.bucket !== 'overdue').length;
  byId('todoCount').textContent = `${entries.length} 项`;
  byId('todoSummary').textContent = `显示 ${entries.length} 项 / 已采集 ${all.length} 项；已过截止时间的作业可通过筛选查看。`;
  const list = byId('todoList');
  const fragment = document.createDocumentFragment();
  if (!entries.length) {
    const empty = node('div', 'empty-state');
    empty.append(node('span', 'empty-icon', '▤'));
    const noData = dashboardResults.length === 0 && dashboardCourses.length === 0;
    empty.append(node('h3', '', noData ? '把两个平台的作业，放在一起' : '当前条件下没有作业'));
    empty.append(node('p', '', noData ? '绑定并采集平台账号后，所有作业会自动汇总到这里。'
      : all.length ? '试试切换平台、修改关键词，或选择“全部作业”。' : '已保存的课程中没有可显示的作业；可更新平台结果。'));
    const action = node('a', '', noData ? '绑定账号并采集' : '更新平台作业');
    action.href = '#accounts';
    action.addEventListener('click', openAccounts);
    empty.append(action);
    fragment.append(empty);
  }
  for (const entry of entries) {
    const row = node('article', `todo-item ${entry.info.bucket}`);
    const icon = node('span', 'todo-item-symbol', '▤');
    icon.setAttribute('aria-hidden', 'true');
    const copy = node('div', 'todo-copy');
    let url = null;
    try {
      const parsed = new URL(entry.url);
      if (parsed.protocol === 'https:' || parsed.protocol === 'http:') url = parsed.href;
    } catch {}
    const title = node(url ? 'a' : 'span', 'todo-title', entry.title);
    if (url) {
      title.href = url;
      title.target = '_blank';
      title.rel = 'noopener noreferrer';
      title.setAttribute('aria-label', `${entry.title}（在新标签页打开作业）`);
    }
    const meta = node('div', 'todo-meta');
    meta.append(node('span', `site-tag ${entry.siteCode === 'scholat' ? 'scholat' : ''}`, siteNames[entry.siteCode] || entry.siteCode),
      node('span', '', entry.courseName), node('span', '', entry.accountName));
    copy.append(title, meta);
    const dates = node('div', 'todo-date');
    const time = node('time', '', formatDeadline(entry.deadline));
    if (entry.deadline) time.dateTime = entry.deadline;
    let label;
    if (entry.info.bucket === 'overdue') label = '已过截止时间';
    else if (entry.info.bucket === 'none') label = '时间待确认';
    else if (entry.info.dateOnly) label = entry.info.days === 0 ? '今天截止' : `还有 ${entry.info.days} 天`;
    else label = remainingLabel(entry.info.dueMs, currentNowMs);
    dates.append(node('span', 'due-label', label), time);
    row.append(icon, copy, dates);
    fragment.append(row);
  }
  list.replaceChildren(fragment);
}

function renderPlatforms(results, snapshot) {
  const key = JSON.stringify([results, credentials, snapshot.running, snapshot.activeCredentialId]);
  if (key === renderedPlatformsKey) return;
  renderedPlatformsKey = key;
  const fragment = document.createDocumentFragment();
  for (const [siteCode, siteName] of Object.entries(siteNames)) {
    const row = node('article', 'platform-item');
    const saved = results.filter(result => result.siteCode === siteCode);
    const count = saved.reduce((sum, result) => sum + (result.progress?.courses || [])
      .reduce((total, course) => total + (course.assignments || []).length, 0), 0);
    const top = node('div', 'platform-top');
    const icon = node('span', `platform-icon ${siteCode === 'scholat' ? 'scholat' : ''}`, siteCode === 'scholat' ? '学' : '砺');
    icon.setAttribute('aria-hidden', 'true');
    top.append(icon, node('span', 'platform-name', siteName), node('span', 'platform-total', saved.length ? `${count} 项作业` : '待采集'));
    row.append(top);
    const accounts = credentials.filter(item => item.siteCode === siteCode);
    const sources = [...accounts.map(account => ({ id: account.id, name: account.displayName })),
      ...saved.filter(item => item.siteCredentialId == null).map(item => ({ id: null, name: item.displayName }))];
    if (!sources.length) row.append(node('p', 'platform-account', '尚未绑定账号，展开下方“账号与采集”添加。'));
    for (const account of sources) {
      const result = saved.find(item => item.siteCredentialId === account.id);
      const info = node('p', 'platform-account');
      info.append(node('strong', '', account.name), document.createElement('br'));
      let message = '还没有已保存结果，请发起采集。';
      if (result) message = `${(result.collectedAt || '时间未知').replace('T', ' ')}${result.status === 'partial' ? '（部分完成，含失败课程）' : ''}`;
      if (snapshot.running && snapshot.activeCredentialId === account.id) message += ' · 正在更新';
      info.append(node('span', result?.status === 'partial' ? 'platform-warning' : '', message));
      row.append(info);
    }
    fragment.append(row);
  }
  byId('platformSummary').replaceChildren(fragment);
}

for (const button of byId('platformFilters').querySelectorAll('button')) {
  button.addEventListener('click', () => {
    todoSite = button.dataset.site;
    for (const other of byId('platformFilters').querySelectorAll('button')) {
      const active = other === button;
      other.classList.toggle('active', active);
      other.setAttribute('aria-pressed', String(active));
    }
    renderTodo();
  });
}
byId('todoSearch').addEventListener('input', renderTodo);
byId('todoDeadlineFilter').addEventListener('change', renderTodo);
for (const link of document.querySelectorAll('a[href="#accounts"]')) link.addEventListener('click', openAccounts);
for (const link of document.querySelectorAll('.nav-link')) {
  link.addEventListener('click', () => {
    for (const other of document.querySelectorAll('.nav-link')) other.classList.toggle('active', other === link);
  });
}

function renderUpcoming(courses, nowMs, hasData = courses.length > 0) {
  const entries = [];
  const seen = new Set();
  for (const course of courses) {
    for (const assignment of course.assignments || []) {
      const info = deadlineInfo(assignment.deadline, nowMs);
      const bucket = info.bucket;
      if (bucket !== 'week' && bucket !== 'fortnight') continue;
      const key = JSON.stringify([course.courseKey, assignment.id ?? assignment.url ?? assignment.title]);
      if (seen.has(key)) continue;
      seen.add(key);
      entries.push({
        bucket,
        courseName: `${siteNames[course.siteCode] || course.siteCode} · ${course.courseName}`,
        title: assignment.title,
        deadline: assignment.deadline,
        dueMs: info.dueMs,
        days: info.days,
        dateOnly: info.dateOnly
      });
    }
  }
  entries.sort((left, right) => left.dueMs - right.dueMs);
  const renderKey = `${Math.floor(nowMs / 60000)}:${hasData}:${JSON.stringify(entries)}`;
  if (renderKey === renderedUpcomingKey) return;
  renderedUpcomingKey = renderKey;

  const week = entries.filter(item => item.bucket === 'week');
  const fortnight = entries.filter(item => item.bucket === 'fortnight');
  byId('weekMetric').textContent = hasData ? week.length : '—';
  byId('fortnightMetric').textContent = hasData ? fortnight.length : '—';
  byId('weekCount').textContent = week.length;
  byId('fortnightCount').textContent = fortnight.length;
  byId('clockNow').textContent = `按北京时间计算 · ${shanghaiDateTime.format(new Date(nowMs))}`;
  renderUpcomingList('weekAssignments', week, hasData, nowMs, '未来 7 天暂无未过期作业。');
  renderUpcomingList('fortnightAssignments', fortnight, hasData, nowMs, '第 8–14 天暂无未过期作业。');
}

function renderUpcomingList(elementId, entries, courseCount, nowMs, emptyMessage) {
  const list = byId(elementId);
  list.replaceChildren();
  if (entries.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'bucket-empty';
    empty.textContent = courseCount > 0 ? emptyMessage : '采集完成后显示近期作业。';
    list.append(empty);
    return;
  }
  const fragment = document.createDocumentFragment();
  for (const entry of entries) {
    const item = document.createElement('article');
    item.className = 'deadline-item';
    const copy = document.createElement('div');
    copy.className = 'deadline-item-copy';
    const title = document.createElement('span');
    title.className = 'deadline-item-title';
    title.textContent = entry.title;
    const course = document.createElement('span');
    course.className = 'deadline-item-course';
    course.textContent = entry.courseName;
    copy.append(title, course);
    const date = document.createElement('time');
    date.className = 'deadline-item-date';
    date.dateTime = entry.deadline;
    if (entry.dateOnly) {
      date.textContent = `${entry.deadline} · ${entry.days === 0 ? '今天截止' : `还有 ${entry.days} 天`}`;
    } else {
      const today = shanghaiDate.format(new Date(entry.dueMs)) === shanghaiDate.format(new Date(nowMs));
      const countdown = remainingLabel(entry.dueMs, nowMs);
      date.textContent = `${shanghaiDateTime.format(new Date(entry.dueMs))} · ${today ? '今天截止 · ' : ''}${countdown}`;
    }
    item.append(copy, date);
    fragment.append(item);
  }
  list.append(fragment);
}

function renderStatus(snapshot) {
  const progress = snapshot.progress;
  const selectedCredentialId = byId('credentialSelect').value
    ? Number(byId('credentialSelect').value) : null;
  const selectedCredential = credentials.find(item => item.id === selectedCredentialId);
  const selectedSiteCode = selectedCredential?.siteCode || byId('credentialSite').value;
  const results = snapshot.results || (snapshot.latestResult ? [snapshot.latestResult] : []);
  const courses = combineCourses(results, snapshot);
  dashboardCourses = courses;
  dashboardResults = results;
  const parsedNow = Date.parse(snapshot.now || '');
  currentNowMs = Number.isFinite(parsedNow) ? parsedNow : Date.now();
  byId('todayLabel').textContent = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'long'
  }).format(new Date(currentNowMs));
  const stage = progress.stage;
  const busy = snapshot.running;
  const failed = stage === 'error' || stage === 'partial';
  const done = stage === 'completed';

  byId('statusTitle').textContent = busy ? '正在处理' : (stage === 'idle' ? '等待开始' : (done ? '采集完成' : (failed ? '采集有异常' : stageLabels[stage] || '等待开始')));
  byId('statusMessage').textContent = progress.message || '';
  byId('statusBadge').textContent = stageLabels[stage] || '处理中';
  byId('statusBadge').className = `status-badge${busy ? ' busy' : ''}${done ? ' done' : ''}${failed ? ' failed' : ''}`;
  byId('statusDot').className = `status-dot${busy ? ' busy' : ''}${done ? ' done' : ''}${failed ? ' failed' : ''}`;
  byId('progressBar').value = progressPercent(progress);
  byId('progressText').textContent = progress.totalCourses > 0
    ? `已处理 ${progress.processedCourses} / ${progress.totalCourses} 门课程 · 课程目录 ${progress.catalogPagesRead} 页`
    : (busy ? stageLabels[stage] || '正在处理' : '准备就绪');
  byId('currentCourse').textContent = progress.currentCourse ? `当前：${progress.currentCourse}` : '';
  byId('startButton').disabled = busy;
  byId('manualLoginButton').disabled = busy || selectedSiteCode === 'scholat';
  const editing = byId('credentialId').value;
  byId('startButtonText').textContent = busy ? '正在采集…' : editing ? '保存并更新作业'
    : selectedCredentialId && !byId('credentialUsername').value ? '更新所选账号作业' : '绑定账号并自动登录';
  byId('credentialSelect').disabled = busy || credentials.length === 0;

  const assignmentCount = courses.reduce((sum, course) => sum + (course.assignments || []).length, 0);
  const emptyCount = courses.filter(course => course.status === 'empty').length;
  const errorCount = courses.filter(course => course.status === 'error').length;
  const hasData = results.length > 0 || courses.length > 0;
  byId('courseMetric').textContent = hasData ? courses.length : '—';
  byId('courseMetricHint').textContent = hasData ? `${results.length} 份账号快照` : '等待采集';
  byId('assignmentMetric').textContent = hasData ? assignmentCount : '—';
  byId('emptyMetric').textContent = hasData ? emptyCount : '—';
  byId('errorMetric').textContent = hasData ? errorCount : '—';
  byId('courseListCount').textContent = courses.length;
  byId('outputDirectory').textContent = '';
  byId('snapshotInfo').textContent = hasData
    ? `正在汇总 ${results.length} 份已保存结果${busy ? '，本轮已读取的课程会实时更新' : '，结果同时保存为 JSON'}`
    : '暂无已保存结果，请展开“账号与采集”更新作业。';

  renderTodo();
  renderPlatforms(results, snapshot);
  renderUpcoming(courses, currentNowMs, hasData);
  renderCoursePanels(courses);
}

function renderCoursePanels(courses) {
  const coursesKey = `${Math.floor(currentNowMs / 60000)}:${JSON.stringify(courses)}`;
  if (coursesKey === renderedCoursesKey && selectedCourseId === renderedSelectedCourseId) return;
  renderCourseList(courses);
  renderAssignments(courses, currentNowMs);
  renderedCoursesKey = coursesKey;
  renderedSelectedCourseId = selectedCourseId;
}

function renderCourseList(courses) {
  const list = byId('courseList');
  list.replaceChildren();
  if (courses.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'empty-state compact';
    const icon = document.createElement('span');
    icon.className = 'empty-icon';
    icon.textContent = '▤';
    const text = document.createElement('p');
    text.textContent = '采集完成后，课程会显示在这里。';
    empty.append(icon, text);
    list.append(empty);
    return;
  }

  if (!courses.some(course => course.courseKey === selectedCourseId)) {
    selectedCourseId = courses.find(course => (course.assignments || []).length > 0)?.courseKey ?? courses[0].courseKey;
  }

  const fragment = document.createDocumentFragment();
  for (const course of courses) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `course-item${course.courseKey === selectedCourseId ? ' active' : ''}`;
    button.setAttribute('aria-pressed', String(course.courseKey === selectedCourseId));
    button.addEventListener('click', () => {
      selectedCourseId = course.courseKey;
      renderCoursePanels(courses);
    });

    const symbol = document.createElement('span');
    symbol.className = 'course-symbol';
    symbol.textContent = course.status === 'error' ? '!' : (course.status === 'empty' ? '—' : '课');
    const copy = document.createElement('span');
    copy.className = 'course-copy';
    const name = document.createElement('span');
    name.className = 'course-name';
    name.textContent = course.courseName;
    const meta = document.createElement('span');
    meta.className = 'course-meta';
    meta.textContent = course.status === 'error'
      ? '采集失败'
      : course.status === 'empty'
        ? '没有作业活动'
        : `${course.assignmentCount ?? 0} 项作业`;
    const source = document.createElement('span');
    source.className = 'course-meta';
    source.textContent = `${siteNames[course.siteCode] || course.siteCode} · ${course.accountName}`;
    copy.append(name, source, meta);
    const trailing = document.createElement('span');
    trailing.className = 'course-trailing';
    trailing.textContent = '›';
    button.append(symbol, copy, trailing);
    fragment.append(button);
  }
  list.append(fragment);
}

function renderAssignments(courses, nowMs) {
  const course = courses.find(item => item.courseKey === selectedCourseId);
  const content = byId('assignmentContent');
  content.replaceChildren();
  if (!course) {
    byId('assignmentHeading').textContent = '作业列表';
    byId('assignmentCount').textContent = '0 项';
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    const icon = document.createElement('span');
    icon.className = 'empty-icon';
    icon.textContent = '✓';
    const heading = document.createElement('h3');
    heading.textContent = '还没有课程结果';
    const description = document.createElement('p');
    description.textContent = '展开“账号与采集”更新作业，课程会自动出现在这里。';
    empty.append(icon, heading, description);
    content.append(empty);
    return;
  }

  const assignments = course.assignments || [];
  byId('assignmentHeading').textContent = course.courseName;
  byId('assignmentCount').textContent = `${assignments.length} 项`;
  if (course.status === 'error') {
    const error = document.createElement('div');
    error.className = 'course-error';
    error.textContent = course.error || '读取这门课程时发生错误。';
    content.append(error);
  }
  if (assignments.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    const icon = document.createElement('span');
    icon.className = 'empty-icon';
    icon.textContent = course.status === 'error' ? '!' : '✓';
    const heading = document.createElement('h3');
    heading.textContent = course.status === 'error' ? '这门课程未能读取' : '这门课程没有作业活动';
    const description = document.createElement('p');
    description.textContent = course.status === 'error'
      ? '其他课程的采集结果仍可查看。'
      : '课程已成功检查；如果课程使用测验等其他活动，这里不会显示它们。';
    empty.append(icon, heading, description);
    content.append(empty);
    return;
  }

  const wrap = document.createElement('div');
  wrap.className = 'assignment-table-wrap';
  const table = document.createElement('table');
  table.className = 'assignment-table';
  const thead = document.createElement('thead');
  const headerRow = document.createElement('tr');
  for (const label of ['作业名称', '截止时间']) {
    const th = document.createElement('th');
    th.scope = 'col';
    th.textContent = label;
    headerRow.append(th);
  }
  thead.append(headerRow);
  const tbody = document.createElement('tbody');
  for (const assignment of assignments) {
    const row = document.createElement('tr');
    const titleCell = document.createElement('td');
    const title = document.createElement('span');
    title.className = 'assignment-title';
    title.textContent = assignment.title;
    const id = document.createElement('span');
    id.className = 'assignment-id';
    id.textContent = `作业编号 ${assignment.id}`;
    titleCell.append(title, id);
    const deadlineCell = document.createElement('td');
    const deadline = document.createElement('span');
    const bucket = deadlineBucket(assignment.deadline, nowMs);
    const colorClass = bucket === 'week' || bucket === 'fortnight' || bucket === 'overdue'
      ? ` ${bucket}` : '';
    deadline.className = `deadline${colorClass}${assignment.deadline ? '' : ' missing'}`;
    deadline.textContent = formatDeadline(assignment.deadline);
    deadlineCell.append(deadline);
    if (assignment.deadlineRaw && assignment.deadlineRaw !== assignment.deadline) {
      const raw = document.createElement('span');
      raw.className = 'deadline-raw';
      raw.textContent = `页面显示：${assignment.deadlineRaw}`;
      deadlineCell.append(raw);
    }
    row.append(titleCell, deadlineCell);
    tbody.append(row);
  }
  table.append(thead, tbody);
  wrap.append(table);
  content.append(wrap);
}

async function refresh() {
  if (polling) return;
  polling = true;
  const generation = sessionGeneration;
  try {
    const credentialId = byId('credentialSelect').value;
    const query = credentialId ? `?credentialId=${encodeURIComponent(credentialId)}` : '';
    const response = await fetch(`/api/status${query}`, { cache: 'no-store' });
    if (generation !== sessionGeneration) return;
    if (response.status === 401) {
      showLogin();
      return;
    }
    if (!response.ok) throw new Error('本机服务暂时无法读取状态。');
    const snapshot = await response.json();
    if (generation === sessionGeneration) renderStatus(snapshot);
  } catch (error) {
    if (generation !== sessionGeneration) return;
    byId('statusTitle').textContent = '连接本机服务失败';
    byId('statusMessage').textContent = '请确认启动采集器的命令窗口仍在运行，然后刷新页面。';
    byId('statusBadge').textContent = '未连接';
    byId('statusBadge').className = 'status-badge failed';
    byId('statusDot').className = 'status-dot failed';
    byId('startButton').disabled = true;
    byId('manualLoginButton').disabled = true;
  } finally {
    polling = false;
  }
}

function showLogin(message = '') {
  sessionGeneration++;
  byId('appShell').hidden = true;
  byId('loginScreen').hidden = false;
  byId('loginError').textContent = message;
  byId('credentialPassword').value = '';
  credentials = [];
  dashboardCourses = [];
  dashboardResults = [];
  selectedCourseId = null;
  renderedCoursesKey = null;
  renderedUpcomingKey = null;
  renderedTodoKey = null;
  renderedPlatformsKey = null;
  byId('todoSearch').value = '';
  byId('todoDeadlineFilter').value = 'upcoming';
  todoSite = 'all';
  for (const button of byId('platformFilters').querySelectorAll('button')) {
    const active = button.dataset.site === 'all';
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', String(active));
  }
  resetCredentialForm();
  byId('credentialMessage').textContent = '';
  renderCredentialList();
  renderCredentialSelect();
  renderStatus({ running: false, progress: { stage: 'idle', courses: [] }, results: [] });
  if (statusTimer !== null) {
    window.clearInterval(statusTimer);
    statusTimer = null;
  }
}

async function showApp(username) {
  const generation = ++sessionGeneration;
  byId('loginScreen').hidden = true;
  byId('appShell').hidden = false;
  byId('currentAppUser').textContent = username;
  byId('loginPassword').value = '';
  await loadCredentials();
  if (generation !== sessionGeneration || byId('appShell').hidden) return;
  await refresh();
  if (generation === sessionGeneration && !byId('appShell').hidden && statusTimer === null) {
    statusTimer = window.setInterval(refresh, 1000);
  }
}

async function initializeLogin() {
  try {
    const response = await fetch('/api/session', { cache: 'no-store' });
    const session = await response.json();
    if (session.authenticated) await showApp(session.username);
    else showLogin();
  } catch {
    showLogin('无法连接本机服务，请确认启动窗口仍在运行后刷新页面。');
  }
}

byId('loginForm').addEventListener('submit', async event => {
  event.preventDefault();
  const button = byId('loginButton');
  button.disabled = true;
  byId('loginError').textContent = '';
  try {
    const response = await fetch('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: byId('loginUsername').value.trim(),
        password: byId('loginPassword').value
      })
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || '登录失败。');
    await showApp(result.username);
  } catch (error) {
    byId('loginError').textContent = error.message || '登录失败，请重试。';
  } finally {
    button.disabled = false;
  }
});

byId('logoutButton').addEventListener('click', async () => {
  try {
    await fetch('/api/logout', { method: 'POST' });
  } finally {
    showLogin();
  }
});

async function startCollection(mode, credentialId = '') {
  byId('startButton').disabled = true;
  byId('manualLoginButton').disabled = true;
  byId('statusMessage').textContent = mode === 'manual' ? '正在打开手动登录窗口…' : '正在启动自动登录采集…';
  try {
    const query = new URLSearchParams({ mode });
    if (credentialId) query.set('credentialId', credentialId);
    const response = await fetch(`/api/start?${query}`, { method: 'POST' });
    if (response.status === 409) {
      byId('statusMessage').textContent = '已有一轮采集正在运行。';
    } else if (!response.ok) {
      let message = await response.text();
      try { message = JSON.parse(message).message || message; } catch {}
      throw new Error(message || '无法启动采集。');
    }
    await refresh();
  } catch (error) {
    byId('statusTitle').textContent = '启动失败';
    byId('statusMessage').textContent = error.message || '无法连接到本机采集器。';
    byId('statusBadge').textContent = '启动失败';
    byId('statusBadge').className = 'status-badge failed';
    byId('statusDot').className = 'status-dot failed';
    byId('startButton').disabled = false;
    byId('manualLoginButton').disabled = false;
  }
}

byId('manualLoginButton').addEventListener('click', () =>
  startCollection('manual', byId('credentialSelect').value));

function renderCredentialSelect() {
  const select = byId('credentialSelect');
  const previous = select.value;
  select.replaceChildren();
  const placeholder = document.createElement('option');
  placeholder.value = '';
  placeholder.textContent = credentials.length ? '请选择一个账号' : '请先绑定账号';
  select.append(placeholder);
  for (const item of credentials) {
    const option = document.createElement('option');
    option.value = String(item.id);
    option.textContent = `${siteNames[item.siteCode] || item.siteCode} · ${item.displayName}（${item.loginUsername}）`;
    select.append(option);
  }
  if (credentials.some(item => String(item.id) === previous)) select.value = previous;
  else if (credentials.length === 1) select.value = String(credentials[0].id);
  byId('credentialCount').textContent = `${credentials.length} 个账号`;
}

byId('credentialSelect').addEventListener('change', () => {
  if (byId('credentialId').value !== byId('credentialSelect').value) {
    byId('credentialForm').reset();
    byId('credentialId').value = '';
    byId('credentialSite').disabled = false;
    byId('credentialLabel').value = '';
    byId('credentialUsername').value = '';
    byId('credentialPassword').value = '';
    byId('credentialCancel').hidden = true;
  }
  refresh();
});

byId('credentialSite').addEventListener('change', () => refresh());

function renderCredentialList() {
  const list = byId('credentialList');
  list.replaceChildren();
  if (credentials.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'credential-empty';
    empty.textContent = '还没有绑定账号。添加后即可选择账号自动登录采集。';
    list.append(empty);
    return;
  }
  for (const item of credentials) {
    const row = document.createElement('article');
    row.className = 'credential-item';
    const copy = document.createElement('div');
    const title = document.createElement('strong');
    title.textContent = `${siteNames[item.siteCode] || item.siteCode} · ${item.displayName}`;
    const username = document.createElement('span');
    username.textContent = `${item.loginUsername} · 密码已加密保存`;
    copy.append(title, username);
    const actions = document.createElement('div');
    actions.className = 'credential-item-actions';
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.className = 'text-button';
    edit.textContent = '编辑';
    edit.addEventListener('click', () => editCredential(item));
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'text-button danger-text';
    remove.textContent = '删除';
    remove.addEventListener('click', () => deleteCredential(item));
    actions.append(edit, remove);
    row.append(copy, actions);
    list.append(row);
  }
}

async function loadCredentials() {
  const generation = sessionGeneration;
  try {
    const response = await fetch('/api/credentials', { cache: 'no-store' });
    if (generation !== sessionGeneration) return;
    if (response.status === 401) {
      showLogin();
      return;
    }
    if (!response.ok) throw new Error('无法读取已绑定账号。');
    const result = await response.json();
    if (generation !== sessionGeneration) return;
    credentials = result;
    renderCredentialList();
    renderCredentialSelect();
  } catch (error) {
    if (generation !== sessionGeneration) return;
    byId('credentialMessage').textContent = error.message || '读取账号失败。';
  }
}

function editCredential(item) {
  openAccounts();
  byId('credentialId').value = String(item.id);
  byId('credentialSite').value = item.siteCode;
  byId('credentialSite').disabled = true;
  byId('credentialLabel').value = item.displayName;
  byId('credentialUsername').value = item.loginUsername;
  byId('credentialPassword').value = '';
  byId('credentialSelect').value = String(item.id);
  byId('credentialCancel').hidden = false;
  byId('credentialMessage').textContent = '留空学校密码即可保留当前密码。';
  byId('credentialLabel').focus();
}

function resetCredentialForm() {
  byId('credentialForm').reset();
  byId('credentialId').value = '';
  byId('credentialSite').disabled = false;
  byId('credentialCancel').hidden = true;
}

byId('credentialCancel').addEventListener('click', () => {
  resetCredentialForm();
  byId('credentialMessage').textContent = '';
});

byId('credentialForm').addEventListener('submit', async event => {
  event.preventDefault();
  const button = byId('startButton');
  button.disabled = true;
  byId('credentialMessage').textContent = '';
  const id = byId('credentialId').value;
  const siteCode = byId('credentialSite').value;
  const displayName = byId('credentialLabel').value.trim();
  const loginUsername = byId('credentialUsername').value.trim();
  const password = byId('credentialPassword').value;
  const hasFormInput = Boolean(id || displayName || loginUsername || password);
  try {
    let selectedId = id || byId('credentialSelect').value;
    if (hasFormInput) {
      if (!displayName || !loginUsername) throw new Error('请填写账号备注和学校账号。');
      if (!id && !password) throw new Error('新绑定账号请填写学校密码。');
      const response = await fetch('/api/credentials', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          id: id ? Number(id) : null,
          siteCode,
          displayName,
          loginUsername,
          password
        })
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.message || '绑定账号失败。');
      selectedId = String(result.id);
      byId('credentialSelect').value = selectedId;
      byId('credentialMessage').textContent = '账号已安全保存，正在登录采集…';
      await loadCredentials();
      byId('credentialSelect').value = selectedId;
      resetCredentialForm();
    } else if (!selectedId) {
      throw new Error('请填写新账号信息，或选择一个已绑定账号。');
    }
    byId('credentialPassword').value = '';
    await startCollection('auto', selectedId);
  } catch (error) {
    byId('credentialMessage').textContent = error.message || '绑定账号失败。';
    byId('startButton').disabled = false;
    byId('manualLoginButton').disabled = false;
  } finally {
    byId('credentialPassword').value = '';
  }
});

async function deleteCredential(item) {
  if (!window.confirm(`删除“${item.displayName}”的本机绑定？`)) return;
  try {
    const response = await fetch(`/api/credentials/${encodeURIComponent(item.id)}`, { method: 'DELETE' });
    if (!response.ok) throw new Error('删除账号失败。');
    if (byId('credentialId').value === String(item.id)) resetCredentialForm();
    byId('credentialMessage').textContent = '账号绑定已删除。';
    await loadCredentials();
    await refresh();
  } catch (error) {
    byId('credentialMessage').textContent = error.message || '删除账号失败。';
  }
}

initializeLogin();
