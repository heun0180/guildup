import { useEffect, useId, useRef, useState } from "react";
import { useLocation } from "react-router-dom";
import AppLink from "./AppLink.jsx";
import Avatar from "./Avatar.jsx";
import Icon from "./Icon.jsx";
import { supportContext } from "../feedbackForm.js";

export default function UserProfileMenu({ user, onLogout, hasNewAnnouncements = false }) {
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const container = useRef(null);
  const trigger = useRef(null);
  const menu = useRef(null);
  const initialFocus = useRef("first");
  const id = useId();

  useEffect(() => { setOpen(false); }, [location.pathname, location.search, location.hash, user?.id]);
  useEffect(() => {
    if (!open) return;
    const outside = (event) => { if (!container.current?.contains(event.target)) setOpen(false); };
    const escape = (event) => {
      if (event.key === "Escape") { event.preventDefault(); setOpen(false); trigger.current?.focus(); }
    };
    document.addEventListener("pointerdown", outside);
    document.addEventListener("keydown", escape);
    const items = menu.current?.querySelectorAll('[role="menuitem"]');
    items?.[initialFocus.current === "last" ? items.length - 1 : 0]?.focus();
    return () => {
      document.removeEventListener("pointerdown", outside);
      document.removeEventListener("keydown", escape);
    };
  }, [open]);

  if (!user) return null;
  const links = [
    { href: "/communities.html", label: "내 커뮤니티", icon: "users" },
    { href: "/account.html", label: "계정", icon: "settings" },
    { href: "/announcements", label: "GuildUp 공지", icon: "book", newBadge: hasNewAnnouncements },
    { href: "/help", label: "GuildUp 가이드", icon: "help" },
    { href: "/support", label: "문의 / 건의", icon: "message", state: { supportContext: supportContext(location) } },
    ...(user.systemAdmin ? [{ href: "/developer", label: "개발자", icon: "dashboard" }] : []),
  ];

  function navigateMenu(event) {
    if (event.key === "Tab") {
      setOpen(false); trigger.current?.focus();
      return;
    }
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const items = [...menu.current.querySelectorAll('[role="menuitem"]')];
    const current = items.indexOf(document.activeElement);
    const next = event.key === "Home" ? 0 : event.key === "End" ? items.length - 1
      : (current + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length;
    items[next]?.focus();
  }

  return <div className="profile-menu" ref={container} onBlur={(event) => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
  }}>
    <button ref={trigger} className="text-button profile-menu-trigger" type="button"
      aria-label={`${user.nickname || "사용자"} 사용자 메뉴`} aria-haspopup="menu" aria-expanded={open} aria-controls={id}
      onClick={() => { initialFocus.current = "first"; setOpen(!open); }}
      onKeyDown={(event) => {
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
          event.preventDefault(); initialFocus.current = event.key === "ArrowUp" ? "last" : "first";
          setOpen(true);
        }
      }}>
      <Avatar name={user.nickname || "U"} className="header-avatar" />
      <span className="profile-menu-name">{user.nickname || "사용자"}</span>
      <span className="profile-menu-chevron" aria-hidden="true">▾</span>
    </button>
    {open && <div className="panel profile-menu-dropdown">
      <div className="profile-menu-identity"><strong>{user.nickname || "사용자"}</strong></div>
      <div ref={menu} id={id} role="menu" aria-label="사용자 메뉴" onKeyDown={navigateMenu}>
        {links.map(({ href, label, icon, newBadge, state }) => <AppLink key={href} href={href} state={state} role="menuitem"
          tabIndex={-1} className="profile-menu-item" onClick={() => setOpen(false)}>
          <Icon name={icon} size={18} /><span>{label}</span>
          {newBadge && <span className="role-badge profile-menu-new">NEW</span>}
        </AppLink>)}
        <div className="profile-menu-divider" role="separator" />
        <button className="profile-menu-item profile-menu-logout" type="button" role="menuitem" tabIndex={-1}
          onClick={() => { setOpen(false); onLogout(); }}><Icon name="arrow" size={18} /><span>로그아웃</span></button>
      </div>
    </div>}
  </div>;
}
