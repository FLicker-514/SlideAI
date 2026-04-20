<script setup lang="ts">
import { RouterView } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { onMounted } from 'vue'

const auth = useAuthStore()

onMounted(() => {
  if (!auth.user) {
    void auth.fetchUser()
  }
})
</script>

<template>
  <div class="dashboard">
    <aside class="sidebar">
      <div class="sidebar-head">
        <div class="sidebar-title">工作台</div>
        <div v-if="auth.user" class="sidebar-sub">{{ auth.user.userName || auth.user.email }}</div>
      </div>

      <nav class="side-nav">
        <router-link to="/profile" class="side-link" end>个人主页</router-link>
        <router-link to="/files" class="side-link">文件管理</router-link>
        <router-link to="/knowledge" class="side-link">知识库管理</router-link>
        <router-link to="/styles" class="side-link">风格管理</router-link>
        <router-link to="/components" class="side-link">组件管理</router-link>
        <router-link to="/layouts" class="side-link">版式管理</router-link>
        <router-link to="/workshop" class="side-link">制作工坊</router-link>
      </nav>
    </aside>

    <section class="content">
      <div class="content-inner">
        <RouterView />
      </div>
    </section>
  </div>
</template>

<style scoped>
.dashboard {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 0;
  height: 100%;
  min-height: 0;
  align-items: stretch;
  align-self: stretch;
}

.sidebar {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--color-border);
  background: var(--color-background-soft);
  box-shadow: 2px 0 8px rgba(0, 0, 0, 0.04);
}

.sidebar-head {
  padding: 18px 16px;
  border-bottom: 1px solid var(--color-border);
  background: linear-gradient(180deg, rgba(37, 99, 235, 0.08), rgba(37, 99, 235, 0));
}

.sidebar-title {
  font-weight: 700;
  color: var(--color-heading);
  font-size: 1.05rem;
}

.sidebar-sub {
  margin-top: 6px;
  font-size: 0.85rem;
  color: var(--color-text-muted);
  word-break: break-all;
}

.side-nav {
  display: flex;
  flex-direction: column;
  padding: 10px;
  gap: 6px;
  flex: 1;
}

.side-link {
  padding: 10px 12px;
  border-radius: var(--radius-md);
  color: var(--color-text);
  font-weight: 600;
  font-size: 0.95rem;
}

.side-link:hover {
  background: var(--color-background-mute);
  color: var(--primary);
}

.side-link.router-link-exact-active {
  background: var(--primary-light);
  color: var(--primary);
}

.content {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 12px 16px;
  overflow: hidden;
}

.content-inner {
  flex: 1 1 0;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  width: 100%;
}

/* 子页面根元素占满 content-inner，内容多时在内部滚动 */
.content-inner :deep(> *) {
  flex: 1 1 0;
  min-height: 0;
  min-width: 0;
  overflow: auto;
  width: 100%;
  display: flex;
  flex-direction: column;
}

@media (max-width: 900px) {
  .dashboard {
    grid-template-columns: 1fr;
    min-height: auto;
  }
  .sidebar {
    border-right: none;
    border-bottom: 1px solid var(--color-border);
  }
  .side-nav {
    flex: none;
    flex-direction: row;
    flex-wrap: wrap;
  }
  .content {
    padding: 10px 12px;
  }
}
</style>
