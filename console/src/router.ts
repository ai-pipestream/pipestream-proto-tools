import { createRouter, createWebHistory } from 'vue-router'

// Subjects may contain slashes ("example/person.proto"); links are built
// with encodeURIComponent and the catch-all decodes on match.
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/schema-registry' },
    {
      path: '/schema-registry',
      component: () => import('./App.vue'),
      children: [
        {
          path: '',
          name: 'schema-registry-subjects',
          component: () => import('./views/SubjectsView.vue'),
        },
        {
          path: 'subjects/:subject(.*)',
          name: 'schema-registry-subject',
          component: () => import('./views/SubjectDetailView.vue'),
        },
      ],
    },
  ],
})
