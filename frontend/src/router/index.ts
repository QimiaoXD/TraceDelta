import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import MainLayout from '../layouts/MainLayout.vue'
import LoginView from '../views/LoginView.vue'
import DatasetsView from '../views/DatasetsView.vue'
import DatasetDetailView from '../views/DatasetDetailView.vue'
import ProvidersView from '../views/ProvidersView.vue'
import HttpTargetsView from '../views/HttpTargetsView.vue'
import RunsView from '../views/RunsView.vue'
import RunDetailView from '../views/RunDetailView.vue'
import TraceDetailView from '../views/TraceDetailView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
    },
    {
      path: '/',
      component: MainLayout,
      redirect: '/datasets',
      children: [
        {
          path: 'datasets',
          name: 'datasets',
          component: DatasetsView,
        },
        {
          path: 'datasets/:id',
          name: 'dataset-detail',
          component: DatasetDetailView,
        },
        {
          path: 'providers',
          name: 'providers',
          component: ProvidersView,
        },
        {
          path: 'http-targets',
          name: 'http-targets',
          component: HttpTargetsView,
        },
        {
          path: 'runs',
          name: 'runs',
          component: RunsView,
        },
        {
          path: 'runs/:id',
          name: 'run-detail',
          component: RunDetailView,
        },
        {
          path: 'traces/:traceId',
          name: 'trace-detail',
          component: TraceDetailView,
        },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.path !== '/login' && !auth.isLoggedIn) {
    return '/login'
  }
  if (to.path === '/login' && auth.isLoggedIn) {
    return '/datasets'
  }
  return true
})

export default router
