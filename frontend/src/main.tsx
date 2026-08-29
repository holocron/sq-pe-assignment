import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { ErrorBoundary } from './components/ErrorBoundary'
import './index.css'

const container = document.getElementById('root')
if (!container) {
  throw new Error('Root container #root is missing from index.html')
}

/* Outermost guard. React 19 unmounts the entire root when a render throws with
   nothing above it to catch it, so without this a single bad field access
   blanks the page. Anything the in-router boundary cannot see — a provider or
   the router itself — lands here and still gets a readable panel. */
createRoot(container).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
