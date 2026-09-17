import { Outlet, Route, Routes, useLocation } from "react-router-dom";
import LoginPage from "./pages/LoginPage.jsx";
import CommunitiesPage from "./pages/CommunitiesPage.jsx";
import CommunityDashboardPage from "./pages/CommunityDashboardPage.jsx";
import DiscordConnectPage from "./pages/DiscordConnectPage.jsx";
import MembersPage from "./pages/MembersPage.jsx";
import CommunitySettingsPage from "./pages/CommunitySettingsPage.jsx";
import GameNicknameSettingsPage from "./pages/GameNicknameSettingsPage.jsx";
import DiscordMemberRoleSettingsPage from "./pages/DiscordMemberRoleSettingsPage.jsx";
import ActivityRuleSettingsPage from "./pages/ActivityRuleSettingsPage.jsx";
import CommunityRoleSettingsPage from "./pages/CommunityRoleSettingsPage.jsx";
import MemberActivitiesPage from "./pages/MemberActivitiesPage.jsx";
import MemberActivityDetailPage from "./pages/MemberActivityDetailPage.jsx";
import TeamMakerPage from "./pages/TeamMakerPage.jsx";
import IntegrationsPage from "./pages/IntegrationsPage.jsx";
import DiscordDmPage from "./pages/DiscordDmPage.jsx";
import DiscordVoiceActivityPage from "./pages/DiscordVoiceActivityPage.jsx";
import RankingsPage from "./pages/RankingsPage.jsx";
import KillCompetitionsPage from "./pages/KillCompetitionsPage.jsx";
import FeedbackPage from "./pages/FeedbackPage.jsx";
import CommunityNewsPage from "./pages/CommunityNewsPage.jsx";
import BingoPage from "./pages/BingoPage.jsx";
import AboutPage from "./pages/AboutPage.jsx";
import TermsPage from "./pages/TermsPage.jsx";
import PrivacyPage from "./pages/PrivacyPage.jsx";
import CommunityRouteGuard from "./components/CommunityRouteGuard.jsx";
import DashboardLayout from "./components/DashboardLayout.jsx";
import AppLink from "./components/AppLink.jsx";
import { CommunityProvider, useCommunity } from "./community/CommunityContext.jsx";

const activeByPath = {
  "/community-dashboard.html": "dashboard",
  "/community-news.html": "news",
  "/members.html": "members",
  "/rankings.html": "rankings",
  "/kill-competitions.html": "kill-competitions",
  "/bingos.html": "bingos",
  "/feedback.html": "feedback",
  "/member-activities.html": "activity",
  "/member-activity.html": "activity",
  "/team-maker.html": "team-maker",
  "/integrations.html": "integrations",
  "/discord-dm.html": "integrations",
  "/discord-voice-activity.html": "integrations",
  "/community-settings.html": "settings",
  "/discord-member-role-settings.html": "settings",
  "/game-nickname-settings.html": "settings",
  "/activity-rule-settings.html": "settings",
  "/community-role-settings.html": "settings",
};

function CommunityShell() {
  const location = useLocation();
  const state = useCommunity();
  const active = location.pathname === "/members.html"
    && new URLSearchParams(location.search).get("discordRoles") === "true"
    ? "roles"
    : activeByPath[location.pathname];

  return (
    <DashboardLayout persistent active={active} communityId={state.communityId} community={state.community}>
      <CommunityRouteGuard>
        <Outlet />
      </CommunityRouteGuard>
    </DashboardLayout>
  );
}

function CommunityRoutes() {
  const location = useLocation();
  const communityId = new URLSearchParams(location.search).get("communityId") ?? "invalid";
  return (
    <CommunityProvider key={communityId}>
      <CommunityShell />
    </CommunityProvider>
  );
}

function NotFound() {
  return (
    <main className="public-main login-main">
      <section className="login-card">
        <h1>페이지를 찾을 수 없습니다.</h1>
        <p className="login-description">요청한 GuildUp 화면을 찾을 수 없습니다.</p>
        <AppLink href="/communities.html">내 커뮤니티로 돌아가기</AppLink>
      </section>
    </main>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LoginPage />} />
      <Route path="/login.html" element={<LoginPage />} />
      <Route path="/about" element={<AboutPage />} />
      <Route path="/terms" element={<TermsPage />} />
      <Route path="/privacy" element={<PrivacyPage />} />
      <Route path="/communities.html" element={<CommunitiesPage />} />
      <Route element={<CommunityRoutes />}>
        <Route path="/community-dashboard.html" element={<CommunityDashboardPage />} />
        <Route path="/community-news.html" element={<CommunityNewsPage />} />
        <Route path="/discord-connect.html" element={<DiscordConnectPage />} />
        <Route path="/members.html" element={<MembersPage />} />
        <Route path="/community-settings.html" element={<CommunitySettingsPage />} />
        <Route path="/discord-member-role-settings.html" element={<DiscordMemberRoleSettingsPage />} />
        <Route path="/game-nickname-settings.html" element={<GameNicknameSettingsPage />} />
        <Route path="/activity-rule-settings.html" element={<ActivityRuleSettingsPage />} />
        <Route path="/community-role-settings.html" element={<CommunityRoleSettingsPage />} />
        <Route path="/member-activities.html" element={<MemberActivitiesPage />} />
        <Route path="/member-activity.html" element={<MemberActivityDetailPage />} />
        <Route path="/team-maker.html" element={<TeamMakerPage />} />
        <Route path="/integrations.html" element={<IntegrationsPage />} />
        <Route path="/discord-dm.html" element={<DiscordDmPage />} />
        <Route path="/discord-voice-activity.html" element={<DiscordVoiceActivityPage />} />
        <Route path="/rankings.html" element={<RankingsPage />} />
        <Route path="/kill-competitions.html" element={<KillCompetitionsPage />} />
        <Route path="/bingos.html" element={<BingoPage />} />
        <Route path="/feedback.html" element={<FeedbackPage />} />
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
