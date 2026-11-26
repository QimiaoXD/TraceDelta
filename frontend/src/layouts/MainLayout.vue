<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeMenu = computed(() => {
  if (route.path.startsWith('/providers')) return '/providers'
  return '/datasets'
})

function handleLogout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="layout-root">
    <el-aside width="220px" class="layout-aside">
      <div class="brand">
        <span class="brand-logo">Δ</span>
        <div>
          <div class="brand-title">TraceDelta</div>
          <div class="brand-sub">AI 质量保障平台</div>
        </div>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#0f172a"
        text-color="#cbd5e1"
        active-text-color="#38bdf8"
        class="side-menu"
      >
        <el-menu-item index="/datasets">数据集与版本</el-menu-item>
        <el-menu-item index="/providers">模型渠道与调试</el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <div class="header-left">M1 控制台 · 数据集版本 &amp; 确定性 Mock 调试</div>
        <div class="header-right">
          <el-tag type="info" effect="plain">{{ auth.username || 'admin' }}</el-tag>
          <el-button link type="danger" @click="handleLogout">退出登录</el-button>
        </div>
      </el-header>
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout-root {
  height: 100vh;
}
.layout-aside {
  background-color: #0f172a;
  color: #f8fafc;
  display: flex;
  flex-direction: column;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 18px;
  border-bottom: 1px solid #1e293b;
}
.brand-logo {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: linear-gradient(135deg, #0284c7, #38bdf8);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 18px;
  color: #fff;
}
.brand-title {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.3px;
}
.brand-sub {
  font-size: 11px;
  color: #94a3b8;
}
.side-menu {
  border-right: none;
  flex: 1;
}
.layout-header {
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}
.header-left {
  font-size: 14px;
  color: #475569;
  font-weight: 500;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.layout-main {
  padding: 24px;
  overflow-y: auto;
}
</style>
