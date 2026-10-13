<script setup lang="ts">
const projectName = '服务部管理系统'
</script>

<template>
  <main class="welcome">
    <section>
      <h1>{{ projectName }}</h1>
      <p>项目基础结构已建立，下一步将实现登录、人员和工作管理。</p>
      <el-tag type="success">Development</el-tag>
    </section>
  </main>
</template>

<style scoped>
.welcome {
  min-height: 100vh;
  display: grid;
  place-items: center;
  color: #303133;
  background: #f5f7fa;
  font-family: system-ui, sans-serif;
}

section {
  max-width: 560px;
  padding: 40px;
  text-align: center;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 28px rgb(0 0 0 / 8%);
}

h1 {
  margin-top: 0;
}
</style>

