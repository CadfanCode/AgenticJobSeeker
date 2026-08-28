import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { ApplicationQueue } from './pages/ApplicationQueue'
import { ApplicationReview } from './pages/ApplicationReview'
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
        <Route path="/applications" element={<ApplicationQueue />} />
        <Route path="/applications/:id" element={<ApplicationReview />} />
      </Routes>
    </BrowserRouter>
  )
}
