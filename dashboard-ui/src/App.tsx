import { Link, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import ExceptionDetailPage from './pages/ExceptionDetailPage';
import ExceptionsPage from './pages/ExceptionsPage';
import LookupPage from './pages/LookupPage';
import OverviewPage from './pages/OverviewPage';

function NotFound() {
  return (
    <>
      <div className="page-head">
        <h1>Page not found</h1>
      </div>
      <p className="empty">
        That address does not exist. <Link to="/">Go to the overview</Link>.
      </p>
    </>
  );
}

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<OverviewPage />} />
        <Route path="exceptions" element={<ExceptionsPage />} />
        <Route path="exceptions/:id" element={<ExceptionDetailPage />} />
        <Route path="lookup" element={<LookupPage />} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
