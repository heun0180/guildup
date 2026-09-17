import AppLink from "./AppLink.jsx";

export default function SiteFooter({ showAbout = true }) {
  return (
    <footer className="site-footer">
      <div className="footer-inner">
        <p>© 2026 GuildUp. All rights reserved.</p>
        <nav aria-label="서비스 정책">
          {showAbout && <AppLink href="/about">서비스 소개</AppLink>}
          <AppLink href="/terms">이용약관</AppLink>
          <AppLink href="/privacy">개인정보 처리방침</AppLink>
        </nav>
      </div>
    </footer>
  );
}
