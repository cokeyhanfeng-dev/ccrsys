import { createApp } from 'vue'
import App from './App.vue'
import './styles.css'
import { consumeOaCallback } from './oa.mjs'
const oaCallback = consumeOaCallback(window.location, window.history)
createApp(App, { oaCallback }).mount('#root')
