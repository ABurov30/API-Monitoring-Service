<script setup lang="ts">
import ru from 'element-plus/es/locale/lang/ru';
import AppLayout from './layouts/AppLayout.vue';
</script>
<template>
  <el-config-provider :locale="ru"
    ><AppLayout><router-view :key="$route.path" /></AppLayout
  ></el-config-provider>
</template>
