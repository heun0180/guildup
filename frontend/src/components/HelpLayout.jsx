import AppHeader from "./AppHeader.jsx";
import AppLink from "./AppLink.jsx";
import SiteFooter from "./SiteFooter.jsx";

export default function HelpLayout({ title, description, children, detail = false,
  backHref = "/help", backLabel = "GuildUp 가이드" }) {
  return (
    <div className="public-page help-page">
      <AppHeader />
      <main className="public-main help-main">
        {detail && <AppLink className="activity-back-link" href={backHref}>← {backLabel}</AppLink>}
        <header className="help-heading">
          <p className="eyebrow">GuildUp Guide</p>
          <h1>{title}</h1>
          {description && <p>{description}</p>}
        </header>
        {children}
      </main>
      <SiteFooter />
    </div>
  );
}

export function HelpSection({ title, children }) {
  return <section className="panel help-section"><h2>{title}</h2>{children}</section>;
}

export function HelpSteps({ items }) {
  return <ol className="help-steps">{items.map((item, index) => <li key={item}><span>{index + 1}</span><p>{item}</p></li>)}</ol>;
}

export function HelpNotice({ children }) {
  return <div className="help-notice"><strong>알아두세요</strong><p>{children}</p></div>;
}

export function HelpFaq({ items }) {
  return <div className="help-faq">{items.map(({ question, answer }) => <details key={question}><summary>{question}</summary><p>{answer}</p></details>)}</div>;
}
