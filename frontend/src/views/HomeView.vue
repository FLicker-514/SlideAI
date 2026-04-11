<script setup lang="ts">
const modules = [
  { name: 'account-service', port: 8081 },
  { name: 'llm-service', port: 8082 },
  { name: 'outline-service', port: 8083 },
  { name: 'style-service', port: 8084 },
  { name: 'document-service', port: 8085 },
  { name: 'template-service', port: 8086 },
  { name: 'component-service', port: 8087 },
  { name: 'rag-service', port: 8088 },
  { name: 'web-search-service', port: 8089 },
]
</script>

<template>
  <main>
    <h1>全栈智能 PPT 生成系统</h1>
    <p>本前端为<strong>架构骨架</strong>：路由与代理已按端口约定预留，业务页面在后续迭代实现。</p>
    <h2>后端模块与端口</h2>
    <ul>
      <li v-for="m in modules" :key="m.name">{{ m.name }} — {{ m.port }}</li>
    </ul>
  </main>
</template>
