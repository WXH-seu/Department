<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api, ApiError, jsonBody } from './api'
const props = defineProps<{ authorities: string[] }>()
const emit = defineEmits<{ expired: [] }>()
type Person = { id: number; username: string; display_name: string; enabled: boolean }
type Group = { id: number; name: string }
type Task = { id: number; title: string; description: string; groupId: number; assigneeId: number; status: string; progress: number; version: number; dueDate: string | null }
const people = ref<Person[]>([]), groups = ref<Group[]>([]), tasks = ref<Task[]>([])
const comments = ref<{id:number;body:string;display_name:string;created_at:string}[]>([])
const audit = ref<{id:number;actor:string;action:string;target:string;created_at:string}[]>([])
const notifications = ref<{id:number;message:string;is_read:boolean}[]>([])
const attachments = ref<{id:number;original_name:string;size_bytes:number}[]>([])
const selected = ref<Task | null>(null), error = ref(''), notice = ref(''), tab = ref('tasks'), search = ref(''), comment = ref(''), groupName = ref('')
const busy = ref(false)
const global = computed(() => props.authorities.some(r => ['ROLE_ADMIN','ROLE_MINISTER'].includes(r)))
const manager = computed(() => global.value || props.authorities.includes('ROLE_LEADER'))
const newUser = reactive({ username: '', password: '', displayName: '', role: 'MEMBER' })
const membership = reactive({ groupId: '', userId: '', position: '', leader: false })
const newTask = reactive({ title: '', description: '', groupId: '', assigneeId: '', dueDate: '' })
const editUser = reactive({ id:'', displayName:'',role:'MEMBER' })
const groupMembers = ref<{id:number;display_name:string;position_name:string;is_leader:boolean}[]>([])
const viewingGroup = ref(''), rename = ref('')
async function saveProfile() { await run(async()=>{await api(`/users/${editUser.id}/profile`,jsonBody('PATCH',{displayName:editUser.displayName}));await load();notice.value='资料已更新'}) }
async function saveRole() { await run(async()=>{await api(`/users/${editUser.id}/role`,jsonBody('PATCH',{role:editUser.role}));await load();notice.value='角色已更新，原会话将在下一次请求时失效'}) }
async function viewMembers() { if(!viewingGroup.value)return;await run(async()=>{groupMembers.value=await api(`/groups/${viewingGroup.value}/members`)}) }
async function renameGroup() { await run(async()=>{await api(`/groups/${viewingGroup.value}`,jsonBody('PATCH',{name:rename.value}));await load();notice.value='小组名称已更新'}) }
async function removeMember(id:number) { await run(async()=>{await api(`/groups/${viewingGroup.value}/members/${id}`,{method:'DELETE'});groupMembers.value=await api(`/groups/${viewingGroup.value}/members`);notice.value='成员已移出小组'}) }
const states: Record<string,string> = { TODO: '待处理', IN_PROGRESS: '进行中', REVIEW: '待验收', DONE: '已完成' }
const filtered = computed(() => tasks.value.filter(t => t.title.toLowerCase().includes(search.value.toLowerCase())))
const taskPage=ref(1),personPage=ref(1),personSearch=ref('')
const taskPages=computed(()=>Math.max(1,Math.ceil(filtered.value.length/10)))
const pageTasks=computed(()=>filtered.value.slice((taskPage.value-1)*10,taskPage.value*10))
const filteredPeople=computed(()=>people.value.filter(p=>(p.display_name+' '+p.username).toLowerCase().includes(personSearch.value.toLowerCase())))
const personPages=computed(()=>Math.max(1,Math.ceil(filteredPeople.value.length/10)))
const pagePeople=computed(()=>filteredPeople.value.slice((personPage.value-1)*10,personPage.value*10))
watch(search,()=>taskPage.value=1);watch(personSearch,()=>personPage.value=1)
const pending = computed(() => tasks.value.filter(t => t.status !== 'DONE').length)
function name(id: number) { return people.value.find(p => p.id === id)?.display_name || `成员 #${id}` }
function group(id: number) { return groups.value.find(g => g.id === id)?.name || `小组 #${id}` }
async function run(action: () => Promise<void>) {
  busy.value = true; error.value = ''; notice.value = ''
  try { await action() } catch (e) {
    if (e instanceof ApiError && e.status === 401) emit('expired')
    error.value = e instanceof Error ? e.message : '网络连接失败，请重试'
  } finally { busy.value = false }
}
async function load() {
  const result = await Promise.all([api<Person[]>('/users'), api<Group[]>('/groups'), api<Task[]>('/tasks'), api<typeof notifications.value>('/notifications')])
  ;[people.value, groups.value, tasks.value, notifications.value] = result
}
async function refresh() { await run(load) }
async function createUser() { await run(async () => { await api('/users',jsonBody('POST',newUser)); newUser.password = ''; newUser.username = ''; newUser.displayName = ''; await load(); notice.value = '账号已创建' }) }
async function createGroup() { await run(async () => { await api('/groups',jsonBody('POST',{name:groupName.value})); groupName.value=''; await load(); notice.value='小组已创建' }) }
async function saveMember() { await run(async () => { await api(`/groups/${membership.groupId}/members`,jsonBody('PUT',{userId:Number(membership.userId),position:membership.position,leader:membership.leader})); notice.value='小组成员已保存' }) }
async function toggle(person: Person) { await run(async () => { await api(`/users/${person.id}/enabled`,jsonBody('PATCH',{enabled:!person.enabled})); await load() }) }
async function createTask() { await run(async () => { await api('/tasks',jsonBody('POST',{...newTask,groupId:Number(newTask.groupId),assigneeId:Number(newTask.assigneeId),dueDate:newTask.dueDate || null})); newTask.title='';newTask.description=''; await load(); notice.value='任务已创建' }) }
async function update(task: Task, action: string) { await run(async () => { await api(`/tasks/${task.id}`,jsonBody('PATCH',{action,progress:Number(task.progress),version:task.version})); await load() }) }
async function openComments(task: Task) { await run(async () => { selected.value=task; comments.value=await api(`/tasks/${task.id}/comments`); attachments.value=await api(`/tasks/${task.id}/attachments`) }) }
async function readNotice(id:number) { await run(async () => { await api(`/notifications/${id}/read`,{method:'POST'}); await load() }) }
async function upload(event:Event) {
  const input=event.target as HTMLInputElement
  if (!input.files?.[0] || !selected.value) return
  const file=input.files[0]
  if (file.size>10*1024*1024) { error.value='附件不能超过 10 MB'; input.value=''; return }
  await run(async () => { const form=new FormData();form.append('file',file);await api(`/tasks/${selected.value!.id}/attachments`,{method:'POST',body:form});attachments.value=await api(`/tasks/${selected.value!.id}/attachments`);notice.value='附件已上传' })
  input.value=''
}
async function addComment() { if (!selected.value) return; await run(async () => { await api(`/tasks/${selected.value!.id}/comments`,jsonBody('POST',{body:comment.value})); comment.value=''; comments.value=await api(`/tasks/${selected.value!.id}/comments`) }) }
async function openAudit() { tab.value='audit'; await run(async () => { audit.value=await api('/audit') }) }
onMounted(refresh)
</script>
<template>
  <nav class="tabs"><button :class="{active:tab==='tasks'}" @click="tab='tasks'">工作任务</button><button :class="{active:tab==='people'}" @click="tab='people'">人员与小组</button><button v-if="global" :class="{active:tab==='audit'}" @click="openAudit">操作日志</button><button class="secondary" :disabled="busy" @click="refresh">刷新</button></nav>
  <p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="notice" role="status" class="notice">{{ notice }}</p>
  <details v-if="notifications.some(n=>!n.is_read)"><summary>未读通知 · {{ notifications.filter(n=>!n.is_read).length }}</summary><div v-for="n in notifications.filter(n=>!n.is_read)" :key="n.id" class="row"><p>{{ n.message }}</p><button class="secondary" :disabled="busy" @click="readNotice(n.id)">标为已读</button></div></details>
  <section v-if="tab==='tasks'">
    <div class="stats"><div><strong>{{ pending }}</strong><span>未完成任务</span></div><div><strong>{{ tasks.filter(t=>t.status==='REVIEW').length }}</strong><span>待验收</span></div><div><strong>{{ tasks.filter(t=>t.status==='DONE').length }}</strong><span>已完成</span></div></div>
    <details v-if="manager"><summary>创建任务</summary><form class="grid-form" @submit.prevent="createTask">
      <label>任务标题<input v-model="newTask.title" required maxlength="200" /></label><label>所属小组<select v-model="newTask.groupId" required><option value="" disabled>选择小组</option><option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</option></select></label>
      <label>负责人<select v-model="newTask.assigneeId" required><option value="" disabled>选择组内成员</option><option v-for="p in people.filter(p=>p.enabled)" :key="p.id" :value="p.id">{{ p.display_name }}</option></select></label><label>截止日期<input v-model="newTask.dueDate" type="date" /></label>
      <label class="wide">任务说明<textarea v-model="newTask.description" maxlength="10000" rows="3" /></label><button :disabled="busy">创建任务</button>
    </form></details>
    <label>查找任务<input v-model="search" placeholder="输入任务标题" /></label>
    <p v-if="!filtered.length" class="muted">{{ busy ? '正在加载…' : '暂无符合条件的任务。' }}</p>
    <article v-for="task in pageTasks" :key="task.id" class="task">
      <div class="row"><h2>{{ task.title }}</h2><span class="badge">{{ states[task.status] }}</span></div><p class="muted">{{ group(task.groupId) }} · {{ name(task.assigneeId) }} · {{ task.dueDate || '未设截止日期' }}</p><p class="description">{{ task.description }}</p>
      <progress :value="task.progress" max="100" :aria-label="`${task.title}进度`" /> <span>{{ task.progress }}%</span>
      <div class="actions"><template v-if="['TODO','IN_PROGRESS'].includes(task.status) && !authorities.includes('ROLE_GUEST')"><input v-model="task.progress" type="number" min="0" max="100" aria-label="进度百分比" /><button :disabled="busy" @click="update(task,'PROGRESS')">保存进度</button><button :disabled="busy" @click="update(task,'SUBMIT')">提交验收</button></template>
        <template v-if="task.status==='REVIEW' && manager"><button :disabled="busy" @click="update(task,'APPROVE')">验收通过</button><button class="secondary" :disabled="busy" @click="update(task,'RETURN')">退回修改</button></template><button class="secondary" :disabled="busy" @click="openComments(task)">评论</button></div>
    </article>
    <section v-if="selected" class="comment-panel"><div class="row"><h2>{{ selected.title }} · 评论与附件</h2><button class="secondary" @click="selected=null">关闭</button></div><p v-if="!comments.length" class="muted">暂无评论</p><article v-for="item in comments" :key="item.id"><strong>{{ item.display_name }}</strong><p class="description">{{ item.body }}</p></article><form v-if="!authorities.includes('ROLE_GUEST')" @submit.prevent="addComment"><label>添加评论<textarea v-model="comment" required maxlength="2000" /></label><button :disabled="busy">发布评论</button></form><h3>附件</h3><p v-for="file in attachments" :key="file.id"><a :href="`/api/v1/tasks/${selected.id}/attachments/${file.id}`">{{ file.original_name }}</a> · {{ Math.ceil(file.size_bytes/1024) }} KB</p><label v-if="!authorities.includes('ROLE_GUEST')">上传附件（最大 10 MB）<input type="file" :disabled="busy" @change="upload" /></label></section>
  </section>
  <div v-if="tab==='tasks'" class="actions"><button class="secondary" :disabled="taskPage<=1" @click="taskPage--">上一页</button><span>{{ taskPage }} / {{ taskPages }}</span><button class="secondary" :disabled="taskPage>=taskPages" @click="taskPage++">下一页</button></div>
  <section v-if="tab==='people'">
    <label>搜索成员<input v-model="personSearch" placeholder="姓名或账号" /></label>
    <div v-if="global" class="forms"><details><summary>新增账号</summary><form @submit.prevent="createUser"><label>账号<input v-model="newUser.username" pattern="[a-zA-Z0-9_.-]{3,64}" required /></label><label>姓名<input v-model="newUser.displayName" maxlength="100" required /></label><label>初始密码<input v-model="newUser.password" type="password" autocomplete="new-password" minlength="12" maxlength="72" required /></label><label>角色<select v-model="newUser.role"><option value="MEMBER">部员</option><option value="LEADER">组长</option><option value="GUEST">访客</option><template v-if="authorities.includes('ROLE_ADMIN')"><option value="MINISTER">部长</option><option value="ADMIN">管理员</option></template></select></label><button :disabled="busy">创建账号</button></form></details>
      <details><summary>新增小组</summary><form @submit.prevent="createGroup"><label>小组名称<input v-model="groupName" required maxlength="100" /></label><button :disabled="busy">创建小组</button></form></details>
      <details><summary>设置小组成员</summary><form @submit.prevent="saveMember"><label>小组<select v-model="membership.groupId" required><option value="" disabled>选择小组</option><option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</option></select></label><label>成员<select v-model="membership.userId" required><option value="" disabled>选择成员</option><option v-for="p in people" :key="p.id" :value="p.id">{{ p.display_name }}</option></select></label><label>职务<input v-model="membership.position" maxlength="100" /></label><label><input v-model="membership.leader" type="checkbox" /> 担任本组组长（账号需具备组长角色）</label><button :disabled="busy">保存成员关系</button></form></details></div>
    <details v-if="manager"><summary>修改成员资料与角色</summary><form @submit.prevent="saveProfile"><label>成员<select v-model="editUser.id" required><option value="" disabled>选择成员</option><option v-for="p in people" :key="p.id" :value="p.id">{{ p.display_name }}</option></select></label><label>姓名<input v-model="editUser.displayName" required maxlength="100" /></label><button :disabled="busy">保存姓名</button></form><form v-if="authorities.includes('ROLE_ADMIN')" @submit.prevent="saveRole"><label>新的角色<select v-model="editUser.role"><option value="MEMBER">部员</option><option value="LEADER">组长</option><option value="MINISTER">部长</option><option value="ADMIN">管理员</option><option value="GUEST">访客</option></select></label><button :disabled="busy || !editUser.id">更新角色</button></form></details>
    <h2>成员目录</h2><div class="table-wrap"><table><thead><tr><th>姓名</th><th>账号</th><th>状态</th><th v-if="global">操作</th></tr></thead><tbody><tr v-for="p in pagePeople" :key="p.id"><td>{{ p.display_name }}</td><td>{{ p.username }}</td><td>{{ p.enabled ? '启用' : '停用' }}</td><td v-if="global"><button class="secondary" :disabled="busy" @click="toggle(p)">{{ p.enabled ? '停用' : '启用' }}</button></td></tr></tbody></table></div>
    <div class="actions"><button class="secondary" :disabled="personPage<=1" @click="personPage--">上一页</button><span>{{ personPage }} / {{ personPages }}</span><button class="secondary" :disabled="personPage>=personPages" @click="personPage++">下一页</button></div><h2>小组</h2><p v-if="!groups.length" class="muted">尚未加入任何小组。</p><span v-for="g in groups" :key="g.id" class="badge">{{ g.name }}</span>
    <label>查看小组成员<select v-model="viewingGroup" @change="viewMembers"><option value="" disabled>选择小组</option><option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</option></select></label><div v-for="m in groupMembers" :key="m.id" class="row"><p>{{ m.display_name }} · {{ m.position_name || '未设职务' }}{{ m.is_leader ? ' · 组长' : '' }}</p><button v-if="global" class="secondary" :disabled="busy" @click="removeMember(m.id)">移出小组</button></div><form v-if="manager && viewingGroup" @submit.prevent="renameGroup"><label>新的小组名称<input v-model="rename" required maxlength="100" /></label><button :disabled="busy">修改组名</button></form>
  </section>
  <section v-if="tab==='audit'"><h2>最近操作</h2><div class="table-wrap"><table><thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>对象</th></tr></thead><tbody><tr v-for="a in audit" :key="a.id"><td>{{ a.created_at }}</td><td>{{ a.actor }}</td><td>{{ a.action }}</td><td>{{ a.target }}</td></tr></tbody></table></div></section>
</template>
<style scoped>
.tabs { display:flex; gap:8px; flex-wrap:wrap; margin:24px 0; }.tabs button { background:#edf4f1;color:#386456; }.tabs button.active { background:#176c58;color:white; }
.stats { display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin:24px 0; }.stats div { background:#f2f7f4;padding:24px;border-radius:12px; }.stats strong { display:block;font-size:32px; }.stats span { font-size:13px;color:#6e817a; }
details { padding:18px;background:#f7faf8;border:1px solid #e0e9e4;border-radius:10px;margin:12px 0; }summary { cursor:pointer;font-weight:bold; }.grid-form { display:grid;grid-template-columns:1fr 1fr;gap:0 20px; }.wide { grid-column:1/-1; }.task { padding:22px 0;border-bottom:1px solid #e2ebe6; }h2 { font-size:19px; }.badge { display:inline-block;background:#e9f3ec;padding:6px 10px;border-radius:6px;font-size:12px;margin:3px; }.description { white-space:pre-wrap;overflow-wrap:anywhere; }.actions { display:flex;flex-wrap:wrap;gap:8px;margin-top:16px; }.actions input { width:90px; }progress { width:70%;accent-color:#278268; }.comment-panel { border:1px solid #c4dace;padding:24px;margin-top:24px; }.notice { color:#176c58;background:#edf8ef;padding:12px; }.table-wrap { overflow:auto; }table { width:100%;border-collapse:collapse; }td,th { padding:12px;text-align:left;border-bottom:1px solid #e1e9e5; }input[type=checkbox] { width:auto; }.forms { display:grid;grid-template-columns:repeat(3,1fr);gap:12px;align-items:start; }@media(max-width:700px) { .forms,.grid-form { grid-template-columns:1fr; }.stats { gap:6px; }.stats div { padding:12px; } }
</style>
