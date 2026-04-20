<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { accountApi } from '@/api/account'

const router = useRouter()
const email = ref('')
const code = ref('')
const newPassword = ref('')
const errMsg = ref('')
const loading = ref(false)
const codeCooldown = ref(0)
let cooldownTimer: ReturnType<typeof setInterval> | null = null

function clearCooldownTimer() {
  if (cooldownTimer != null) {
    clearInterval(cooldownTimer)
    cooldownTimer = null
  }
}

function startCooldown(seconds = 60) {
  clearCooldownTimer()
  codeCooldown.value = seconds
  cooldownTimer = setInterval(() => {
    codeCooldown.value--
    if (codeCooldown.value <= 0) {
      clearCooldownTimer()
    }
  }, 1000)
}

onUnmounted(() => {
  clearCooldownTimer()
})

async function sendCode() {
  if (!email.value.trim()) {
    errMsg.value = '请先填写邮箱'
    return
  }
  if (codeCooldown.value > 0) return
  errMsg.value = ''
  // 点击后立即开始倒计时；若发送失败则回滚
  startCooldown(60)
  try {
    await accountApi.sendCode(email.value.trim(), 'reset')
  } catch (e: unknown) {
    const ax = e as { response?: { data?: { message?: string } } }
    errMsg.value = ax.response?.data?.message || '发送失败'
    clearCooldownTimer()
    codeCooldown.value = 0
  }
}

async function onSubmit() {
  errMsg.value = ''
  if (!email.value.trim() || !code.value || !newPassword.value) {
    errMsg.value = '请填写全部字段'
    return
  }
  loading.value = true
  try {
    const res = await accountApi.resetPassword({
      email: email.value.trim(),
      verificationCode: code.value.trim(),
      newPassword: newPassword.value
    })
    if (res.data.code === 200) {
      router.push('/')
    } else {
      errMsg.value = res.data.message || '重置失败，请检查验证码是否有效（10 分钟内有效）'
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
      <h1 class="card-title">找回密码</h1>
      <p class="card-desc">输入注册邮箱，获取验证码后设置新密码</p>
      <form class="form" @submit.prevent="onSubmit">
        <label class="label">注册邮箱</label>
        <input v-model="email" type="email" placeholder="请输入注册邮箱" required class="input" />
        <div class="field-row">
          <div class="field-flex">
            <label class="label">验证码</label>
            <input v-model="code" type="text" placeholder="验证码（10 分钟内有效）" required class="input" />
          </div>
          <button
            type="button"
            class="btn-code"
            :disabled="codeCooldown > 0"
            @click="sendCode"
          >
            {{ codeCooldown > 0 ? `${codeCooldown}s 后重新发送` : '发送验证码' }}
          </button>
        </div>
        <label class="label">新密码</label>
        <input v-model="newPassword" type="password" placeholder="请设置新密码" required class="input" />
        <p v-if="errMsg" class="msg error">{{ errMsg }}</p>
        <button type="submit" class="btn btn-primary" :disabled="loading">
          {{ loading ? '提交中…' : '重置密码' }}
        </button>
      </form>
      <div class="footer-links">
        <router-link to="/">返回登录</router-link>
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

.field-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.field-flex {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.btn-code {
  height: 44px;
  padding: 0 16px;
  white-space: nowrap;
  font-size: 0.9rem;
  color: var(--color-text);
  background: var(--color-background-mute);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color var(--transition), opacity var(--transition);
}
.btn-code:hover:not(:disabled) {
  background: var(--color-border);
}
.btn-code:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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
  font-size: 0.9rem;
}
.footer-links a {
  color: var(--color-text-muted);
}
.footer-links a:hover {
  color: var(--primary);
}
</style>
