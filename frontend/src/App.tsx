import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { ApplicationQueue } from './pages/ApplicationQueue'
import { ApplicationReview } from './pages/ApplicationReview'
import { ArchiveEntryPage } from './pages/ArchiveEntryPage'
import { ArchivePage } from './pages/ArchivePage'
import { JobDetail } from './pages/JobDetail'
import { JobList } from './pages/JobList'
import { PreferencesPage } from './pages/PreferencesPage'
import { ProfilePage } from './pages/ProfilePage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<JobList />} />
        <Route path="/jobs/:id" element={<JobDetail />} />
        <Route path="/profile" element={<ProfilePage />} />
        <Route path="/preferences" element={<PreferencesPage />} />
        <Route path="/applications" element={<ApplicationQueue />} />
        <Route path="/applications/:id" element={<ApplicationReview />} />
        <Route path="/archive" element={<ArchivePage />} />
        <Route path="/archive/:id" element={<ArchiveEntryPage />} />
      </Routes>
    </BrowserRouter>
  )
}
