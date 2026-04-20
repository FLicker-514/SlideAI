<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
</script>

<template>
  <div class="app">
    <header class="header">
      <div class="header-inner">
        <router-link to="/" class="brand">智能 PPT 系统</router-link>
        <nav class="nav">
          <router-link v-if="auth.isLoggedIn" to="/profile">个人主页</router-link>
        </nav>
      </div>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.header {
  background: var(--color-background);
  border-bottom: 1px solid var(--color-border);
  box-shadow: 0 1px 0 rgba(0, 0, 0, 0.03);
}

.header-inner {
  max-width: 100%;
  margin: 0 auto;
  padding: 0 16px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  font-size: 1.15rem;
  font-weight: 700;
  color: var(--color-heading);
}
.brand:hover {
  color: var(--primary);
}

.nav {
  display: flex;
  align-items: center;
  gap: 4px;
}

.nav a {
  padding: 8px 14px;
  border-radius: var(--radius-sm);
  color: var(--color-text);
  font-size: 0.95rem;
}
.nav a:hover {
  color: var(--primary);
  background: var(--color-background-mute);
}
.nav a.router-link-active {
  color: var(--primary);
  font-weight: 600;
  background: var(--primary-light);
}

.app-main {
  flex: 1 1 0;
  min-height: 0;
  width: 100%;
  max-width: 100%;
  margin: 0 auto;
  padding: 16px 12px;
  display: flex;
  flex-direction: column;
}

/* 让 main 内的根视图（如工作台）占满高度，导航栏与右侧内容同高 */
.app-main :deep(> *) {
  flex: 1 1 0;
  min-height: 0;
}

@media (min-width: 640px) {
  .app-main {
    padding: 20px 16px;
  }
}
</style>
