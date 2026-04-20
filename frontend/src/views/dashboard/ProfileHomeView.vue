<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { accountApi } from '@/api/account'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const userName = ref('')
const errMsg = ref('')
const successMsg = ref('')
const loading = ref(false)
const deleteConfirm = ref(false)
const deleteLoading = ref(false)

onMounted(async () => {
  await auth.fetchUser()
  if (auth.user) {
    userName.value = auth.user.userName || ''
  }
})

async function saveProfile() {
  errMsg.value = ''
  successMsg.value = ''
  loading.value = true
  try {
    const res = await accountApi.updateProfile({ userName: userName.value.trim() })
    if (res.data.code === 200 && res.data.data) {
      auth.user = res.data.data
      localStorage.setItem('user', JSON.stringify(res.data.data))
      successMsg.value = '保存成功'
    } else {
      errMsg.value = res.data.message || '保存失败'
    }
  } catch (e: unknown) {
    const ax = e as { response?: { data?: { message?: string } } }
    errMsg.value = ax.response?.data?.message || '网络错误'
  } finally {
    loading.value = false
  }
}

function logout() {
  auth.logout()
  router.push('/')
}

async function deleteAccount() {
  if (!deleteConfirm.value) {
    deleteConfirm.value = true
    return
  }
  deleteLoading.value = true
  errMsg.value = ''
  try {
    const res = await accountApi.deleteAccount()
    if (res.data.code === 200) {
      auth.logout()
      router.push('/')
    } else {
      errMsg.value = res.data.message || '注销失败'
    }
  } catch (e: unknown) {
    const ax = e as { response?: { data?: { message?: string } } }
    errMsg.value = ax.response?.data?.message || '网络错误'
  } finally {
    deleteLoading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="card">
      <h1 class="card-title">个人主页</h1>
      <p class="card-desc">管理您的资料与账号</p>

      <div v-if="auth.user" class="profile-section">
        <div class="info-row">
          <span class="info-label">邮箱</span>
          <span class="info-value">{{ auth.user.email }}</span>
        </div>
        <div class="field-group">
          <label class="label">用户名</label>
          <div class="row">
            <input v-model="userName" type="text" placeholder="请输入用户名" class="input" />
            <button
              type="button"
              class="btn btn-primary btn-save"
              :disabled="loading"
              @click="saveProfile"
            >
              {{ loading ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </div>

      <p v-if="successMsg" class="msg success">{{ successMsg }}</p>
      <p v-if="errMsg" class="msg error">{{ errMsg }}</p>

      <div class="actions">
        <button type="button" class="btn btn-outline" @click="logout">退出登录</button>
        <button
          type="button"
          class="btn btn-danger"
          :disabled="deleteLoading"
          @click="deleteAccount"
        >
          {{ deleteConfirm ? (deleteLoading ? '注销中…' : '确认注销账号') : '注销账号' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  width: 100%;
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.card {
  width: 100%;
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
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

.profile-section {
  margin-bottom: 20px;
}

.info-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--color-border);
  margin-bottom: 20px;
}

.info-label {
  font-size: 0.9rem;
  font-weight: 500;
  color: var(--color-text-muted);
  min-width: 64px;
}

.info-value {
  font-size: 0.95rem;
  color: var(--color-text);
}

.field-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.label {
  font-size: 0.9rem;
  font-weight: 500;
  color: var(--color-text);
}

.row {
  display: flex;
  gap: 10px;
}

.input {
  flex: 1;
  height: 44px;
  padding: 0 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-background);
  color: var(--color-text);
  box-sizing: border-box;
}

.btn {
  height: 44px;
  padding: 0 18px;
  font-weight: 600;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color var(--transition), border-color var(--transition), opacity var(--transition);
}
.btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.btn-primary {
  background: var(--primary);
  color: #fff;
  border: none;
}
.btn-primary:hover:not(:disabled) {
  background: var(--primary-hover);
}

.btn-save {
  flex-shrink: 0;
}

.btn-outline {
  background: transparent;
  color: var(--color-text);
  border: 1px solid var(--color-border);
}
.btn-outline:hover {
  background: var(--color-background-mute);
  border-color: var(--color-border-hover);
}

.btn-danger {
  background: transparent;
  color: var(--vt-c-red);
  border: 1px solid var(--vt-c-red);
}
.btn-danger:hover:not(:disabled) {
  background: rgba(220, 38, 38, 0.08);
}

.msg {
  font-size: 0.9rem;
  margin: 0 0 12px;
}
.msg.success {
  color: var(--vt-c-green);
}
.msg.error {
  color: var(--vt-c-red);
}

.actions {
  margin-top: auto;
  padding-top: 20px;
  border-top: 1px solid var(--color-border);
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
</style>
