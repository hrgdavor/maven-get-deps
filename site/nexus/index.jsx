import { addToBody } from '@jsx6/jsx6'
import { NexusApp } from './NexusApp.jsx'

document.addEventListener('DOMContentLoaded', () => {
  const app = new NexusApp()
  addToBody(app)
})
