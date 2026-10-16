<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiError, jsonBody } from './api'
import Workspace from './Workspace.vue'
const user = ref<{ username: string; authorities: string[] } | null>(null)
const username = ref(''), password = ref(''), error = ref('')
const busy = ref(false), loading = ref(true)
const currentPassword = ref(''), newPassword = ref('')
async function changePassword() {
  busy.value=true;error.value=''
  try { await api('/auth/password',jsonBody('POST',{currentPassword:currentPassword.value,newPassword:newPassword.value}));currentPassword.value='';newPassword.value='';user.value=null;error.value='密码已更新，请使用新密码登录' }
  catch(e) { error.value=e instanceof Error?e.message:'修改失败' }
  finally { busy.value=false }
}
async function restore() {
  loading.value = true
  try { user.value = await api('/auth/me') }
  catch (e) { if (!(e instanceof ApiError && e.status === 401)) error.value = e instanceof Error ? e.message : '无法连接服务器' }
  finally { loading.value = false }
}
async function login() {
  busy.value = true; error.value = ''
  try {
    await api('/auth/login', { method: 'POST', body: new URLSearchParams({ username: username.value.trim(), password: password.value }) })
    user.value = await api('/auth/me'); password.value = ''
  } catch (e) { error.value = e instanceof Error ? e.message : '登录失败' }
  finally { busy.value = false }
}
async function logout() {
  busy.value = true; error.value = ''
  try { await api('/auth/logout', { method: 'POST' }); user.value = null }
  catch (e) { if (e instanceof ApiError && e.status === 401) user.value = null; else error.value = e instanceof Error ? e.message : '退出失败' }
  finally { busy.value = false }
}
onMounted(restore)
</script>
<template>
  <main>
    <header><strong>SEU · 服务部</strong><span>人员与工作管理</span></header>
    <section v-if="loading" class="card" aria-live="polite">正在恢复会话…</section>
    <section v-else-if="!user" class="card login">
      <p class="eyebrow">DEPARTMENT WORKSPACE</p><h1>欢迎回来</h1><p class="muted">登录部门账号，开始今天的工作。</p>
      <form @submit.prevent="login">
        <label for="username">账号</label><input id="username" v-model="username" autocomplete="username" required maxlength="64" :disabled="busy" />
        <label for="password">密码</label><input id="password" v-model="password" type="password" autocomplete="current-password" required :disabled="busy" />
        <p v-if="error" role="alert" class="error">{{ error }}</p><button :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
      </form><p class="muted small">账号由部门管理员分配。如需帮助，请联系管理员。</p>
    </section>
    <section v-else class="card">
      <div class="row"><h1>{{ user.username }}，你好</h1><button class="secondary" :disabled="busy" @click="logout">退出登录</button></div>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <Workspace v-if="user.authorities.some(r=>['ROLE_ADMIN','ROLE_MINISTER','ROLE_LEADER','ROLE_MEMBER'].includes(r))" :authorities="user.authorities" @expired="user=null; error='登录已失效，请重新登录'" />
      <p v-else class="muted">当前账号尚无内部工作区权限，请联系管理员。目前没有开放的访客内容。</p>
      <details><summary>账号安全 · 修改密码</summary><form @submit.prevent="changePassword"><label>当前密码<input v-model="currentPassword" type="password" autocomplete="current-password" required /></label><label>新密码<input v-model="newPassword" type="password" autocomplete="new-password" required minlength="12" maxlength="72" /></label><button :disabled="busy">修改密码并重新登录</button></form></details>
    </section>
  </main>
</template>
<style>
* { box-sizing: border-box; }body { margin: 0; background: #f3f6f5; color: #183b36; font-family: 'Microsoft YaHei', system-ui, sans-serif; }
header { padding: 24px 5%; background: #fff; border-bottom: 1px solid #dfe9e5; display: flex; justify-content: space-between; gap: 16px; }
.card { background: #fff; margin: 64px auto; padding: 40px; border: 1px solid #dfe9e5; border-radius: 16px; width: min(1100px, 92%); }.login { max-width: 440px; }
.eyebrow { font-size: 12px; letter-spacing: 2px; color: #538078; }h1 { margin: 12px 0; font-size: 30px; }.muted { color: #71817d; line-height: 1.7; }.small { font-size: 12px; margin-top: 24px; }
label { display: block; font-size: 14px; margin: 22px 0 8px; }input, select, textarea { width: 100%; padding: 12px; border: 1px solid #bdcec8; border-radius: 8px; font: inherit; background: #fff; }
input:focus, select:focus, textarea:focus { outline: 2px solid #59aa94; outline-offset: 2px; }button { border: 0; border-radius: 8px; padding: 12px 20px; background: #176c58; color: white; cursor: pointer; font: inherit; }
form > button { margin-top: 24px; width: 100%; }button:disabled { opacity: .55; cursor: wait; }.secondary { background: #edf4f1; color: #245c4e; }.error { color: #ac3333; background: #fff1f1; padding: 12px; border-radius: 8px; }
.row { display: flex; justify-content: space-between; align-items: center; gap: 16px; }.row > button { flex-shrink:0; }.row h1 { min-width:0; overflow-wrap:anywhere; }@media(max-width: 600px) { .card { padding: 24px; margin: 24px auto; }header { font-size: 13px; }h1 { font-size:24px; } }
</style>
