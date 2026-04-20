<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { accountApi } from '@/api/account'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const email = ref('')
const password = ref('')
const errMsg = ref('')
const loading = ref(false)

async function onSubmit() {
  errMsg.value = ''
  if (!email.value.trim() || !password.value) {
    errMsg.value = '请填写邮箱和密码'
    return
  }
  loading.value = true
  try {
    const res = await accountApi.login({
      email: email.value.trim(),
      password: password.value,
      loginType: 'user'
    })
    const d = res.data
    if (d.code === 200 && d.data) {
      auth.setLogin(d.data.token, d.data.user)
      router.push('/profile')
    } else {
      errMsg.value = d.message || '登录失败'
    }
  } catch (e: unknown) {
    const ax = e as { response?: { data?: { message?: string } } }
    errMsg.value = ax.response?.data?.message || '网络错误'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="card">
      <h1 class="card-title">登录</h1>
      <p class="card-desc">使用邮箱与密码登录您的账号</p>
      <form class="form" @submit.prevent="onSubmit">
        <label class="label">邮箱</label>
        <input v-model="email" type="email" placeholder="请输入邮箱" required class="input" />
        <label class="label">密码</label>
        <input v-model="password" type="password" placeholder="请输入密码" required class="input" />
        <p v-if="errMsg" class="msg error">{{ errMsg }}</p>
        <button type="submit" class="btn btn-primary" :disabled="loading">
          {{ loading ? '登录中…' : '登录' }}
        </button>
      </form>
      <div class="footer-links">
        <router-link to="/register">没有账号？去注册</router-link>
        <router-link to="/reset-password">忘记密码？找回密码</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 56px - 64px);
  padding: 32px 20px;
}

.card {
  width: 100%;
  max-width: 400px;
  padding: 32px 28px;
  background: var(--color-background-soft);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.card-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-heading);
  margin-bottom: 6px;
}

.card-desc {
  font-size: 0.9rem;
  color: var(--color-text-muted);
  margin-bottom: 24px;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.label {
  font-size: 0.9rem;
  font-weight: 500;
  color: var(--color-text);
}

.input {
  width: 100%;
  height: 44px;
  padding: 0 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-background);
  color: var(--color-text);
  box-sizing: border-box;
}

.msg {
  font-size: 0.9rem;
  margin: -4px 0 0;
}
.msg.error {
  color: var(--vt-c-red);
}

.btn {
  height: 44px;
  margin-top: 8px;
  font-weight: 600;
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color var(--transition), opacity var(--transition);
}
.btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
.btn-primary {
  background: var(--primary);
  color: #fff;
}
.btn-primary:hover:not(:disabled) {
  background: var(--primary-hover);
}

.footer-links {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid var(--color-border);
  display: flex;
  justify-content: space-between;
  font-size: 0.9rem;
}
.footer-links a {
  color: var(--color-text-muted);
}
.footer-links a:hover {
  color: var(--primary);
}
</style>
