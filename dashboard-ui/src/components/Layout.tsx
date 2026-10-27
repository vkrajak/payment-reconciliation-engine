import { NavLink, Outlet } from 'react-router-dom';
import { OperatorControl } from './OperatorControl';

export function Layout() {
  return (
    <>
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <header className="topbar">
        <div className="topbar-inner">
          <span className="brand">Reconciliation console</span>
          <nav aria-label="Main">
            <NavLink to="/" end>
              Overview
            </NavLink>
            <NavLink to="/exceptions">Exceptions</NavLink>
            <NavLink to="/lookup">Find a transaction</NavLink>
          </nav>
          <OperatorControl />
        </div>
      </header>
      <main id="main" className="page">
        <Outlet />
      </main>
    </>
  );
}
