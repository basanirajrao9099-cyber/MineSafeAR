import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.jsx'

// The stylesheet is linked from index.html rather than imported here, so these
// sources are valid plain ES modules — see the comment there.
createRoot(document.getElementById('root')).render(<App />)
