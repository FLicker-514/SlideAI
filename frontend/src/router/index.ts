import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const authGuard = (_to: unknown, _from: unknown, next: (arg?: { name: string }) => void) => {
  const auth = useAuthStore()
  if (!auth.isLoggedIn) next({ name: 'login' })
  else next()
}

const layout = () => import('../views/ProfileView.vue')

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'login',
      component: () => import('../views/LoginView.vue')
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../views/RegisterView.vue')
    },
    {
      path: '/reset-password',
      name: 'reset-password',
      component: () => import('../views/ResetPasswordView.vue')
    },
    {
      path: '/profile',
      name: 'profile',
      component: layout,
      meta: { requiresAuth: true },
      beforeEnter: authGuard,
      children: [{ path: '', component: () => import('../views/dashboard/ProfileHomeView.vue') }]
    },
    {
      path: '/files',
      name: 'files-manage',
      component: layout,
      meta: { requiresAuth: true },
      beforeEnter: authGuard,
      children: [{ path: '', component: () => import('../views/dashboard/FilesManageView.vue') }]
    },
    {
      path: '/knowledge',
      name: 'knowledge-manage',
      component: layout,
      meta: { requiresAuth: true },
      beforeEnter: authGuard,
      children: [{ path: '', component: () => import('../views/dashboard/KnowledgeManageView.vue') }]
    },
    {
      path: '/styles',
      name: 'style-manage',
      component: layout,
      meta: { requiresAuth: true },
      beforeEnter: authGuard,
      children: [{ path: '', component: () => import('../views/dashboard/StyleManageView.vue') }]
    },
    {
      path: '/components',
      name: 'component-manage',
      component: layout,
      meta: { requiresAuth: true },
      beforeEnter: authGuard,
      children: [{ path: '', component: () => import('../views/dashboard/ComponentManageView.vue') }]
    },
    {
      path: '/layouts',
      name: 'layout-manage',
      component: layout,
      meta: { requiresAuth: true },
      beforeEnter: authGuard,
      children: [
        { path: '', name: 'layout-manage-home', component: () => import('../views/dashboard/LayoutManageView.vue') },
        { path: 'add', name: 'layout-add', component: () => import('../views/dashboard/LayoutAddView.vue') }
      ]
    },
    {
      path: '/workshop',
      name: 'workshop',
      component: layout,
      meta: { requiresAuth: true },
      beforeEnter: authGuard,
      children: [{ path: '', component: () => import('../views/dashboard/WorkshopView.vue') }]
    }
  ]
})

export default router
