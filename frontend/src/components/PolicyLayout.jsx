import AppHeader from "./AppHeader.jsx";
import SiteFooter from "./SiteFooter.jsx";

export default function PolicyLayout({ title, effectiveDate, children }) {
  return (
    <div className="public-page">
      <AppHeader />
      <main className="public-main policy-main">
        <header className="policy-heading">
          <h1>{title}</h1>
          {effectiveDate && <p>시행일: {effectiveDate}</p>}
        </header>
        <article className="policy-content">{children}</article>
      </main>
      <SiteFooter />
    </div>
  );
}
