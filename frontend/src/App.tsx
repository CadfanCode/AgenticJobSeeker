import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { JobDetail } from './pages/JobDetail'
import { JobList } from './pages/JobList'
import { ProfilePage } from './pages/ProfilePage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<JobList />} />
        <Route path="/jobs/:id" element={<JobDetail />} />
        <Route path="/profile" element={<ProfilePage />} />
      </Routes>
    </BrowserRouter>
  )
}
