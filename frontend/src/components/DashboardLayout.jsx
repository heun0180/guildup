import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import AppHeader from "./AppHeader.jsx";
import Sidebar from "./Sidebar.jsx";

export default function DashboardLayout({ active, communityId, community, loadCommunity = true, onError, children }) {
  const [resolvedCommunity, setResolvedCommunity] = useState(community ?? null);
  const validId = /^\d+$/.test(communityId ?? "");

  useEffect(() => {
    if (community) setResolvedCommunity(community);
  }, [community]);

  useEffect(() => {
    if (!loadCommunity || !validId) return;

    api(`/api/communities/${encodeURIComponent(communityId)}`)
      .then(setResolvedCommunity)
      .catch((error) => {
        if (!redirectToLogin(error)) onError?.(error.message);
      });
  }, [communityId, loadCommunity, onError, validId]);

  return (
    <div className="app-shell">
      <AppHeader actions onError={onError} />
      <div className="dashboard-shell">
        <Sidebar community={resolvedCommunity} communityId={communityId} active={active} />
        <main className="dashboard-main">{children}</main>
      </div>
    </div>
  );
}
