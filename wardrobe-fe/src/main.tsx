import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'

async function mount() {
  if (import.meta.env.DEV) {
    try {
      const { startDevFixtures } = await import('./dev/browser')
      await startDevFixtures()
    } catch (error) {
      console.warn('Availability fixtures could not start. Using the real API.', error)
    }
  }

  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}

void mount()
