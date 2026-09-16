import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useCommunity } from "../community/CommunityContext.jsx";
import AppHeader from "./AppHeader.jsx";
import Sidebar from "./Sidebar.jsx";

export default function DashboardLayout({ active, communityId, community, persistent = false, children }) {
  const shared = useCommunity();
  const navigate = useNavigate();

  useEffect(() => {
    if (!persistent && community && shared) shared.setCommunity(community);
  }, [community, persistent, shared?.setCommunity]);

  if (shared && !persistent) return children;

  const resolvedCommunity = shared?.community ?? community ?? null;
  const resolvedCommunityId = shared?.communityId ?? communityId;

  function navigateInternalLink(event) {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const anchor = event.target.closest("a[href]");
    if (!anchor || anchor.target || anchor.hasAttribute("download")) return;
    const destination = new URL(anchor.href, window.location.href);
    if (destination.origin !== window.location.origin) return;
    if (destination.pathname !== "/" && !destination.pathname.endsWith(".html")) return;
    if (destination.pathname === window.location.pathname
      && destination.search === window.location.search
      && destination.hash) return;
    event.preventDefault();
    navigate(`${destination.pathname}${destination.search}${destination.hash}`);
  }

  return (
    <div className="app-shell" onClick={persistent ? navigateInternalLink : undefined}>
      <AppHeader actions communityId={resolvedCommunityId} />
      <div className="dashboard-shell">
        <Sidebar community={resolvedCommunity} communityId={resolvedCommunityId} active={active}
                 loading={shared?.loading ?? !resolvedCommunity} />
        <main className="dashboard-main">{children}</main>
      </div>
    </div>
  );
}
