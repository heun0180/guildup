import LoginPage from "./pages/LoginPage.jsx";
import CommunitiesPage from "./pages/CommunitiesPage.jsx";
import CommunityDashboardPage from "./pages/CommunityDashboardPage.jsx";
import DiscordConnectPage from "./pages/DiscordConnectPage.jsx";
import MembersPage from "./pages/MembersPage.jsx";
import CommunitySettingsPage from "./pages/CommunitySettingsPage.jsx";
import GameNicknameSettingsPage from "./pages/GameNicknameSettingsPage.jsx";
import MemberActivitiesPage from "./pages/MemberActivitiesPage.jsx";
import MemberActivityDetailPage from "./pages/MemberActivityDetailPage.jsx";
import TeamMakerPage from "./pages/TeamMakerPage.jsx";
import IntegrationsPage from "./pages/IntegrationsPage.jsx";
import DiscordDmPage from "./pages/DiscordDmPage.jsx";
import DiscordVoiceActivityPage from "./pages/DiscordVoiceActivityPage.jsx";

import CommunityNewsPage from "./pages/CommunityNewsPage.jsx";

const pages = {
  "/community-news.html": CommunityNewsPage,
  "/": LoginPage,
  "/login.html": LoginPage,
  "/communities.html": CommunitiesPage,
  "/community-dashboard.html": CommunityDashboardPage,
  "/discord-connect.html": DiscordConnectPage,
  "/members.html": MembersPage,
  "/community-settings.html": CommunitySettingsPage,
  "/game-nickname-settings.html": GameNicknameSettingsPage,
  "/member-activities.html": MemberActivitiesPage,
  "/member-activity.html": MemberActivityDetailPage,
  "/team-maker.html": TeamMakerPage,
  "/integrations.html": IntegrationsPage,
  "/discord-dm.html": DiscordDmPage,
  "/discord-voice-activity.html": DiscordVoiceActivityPage,
};

export default function App() {
  const Page = pages[window.location.pathname];

  if (!Page) {
    return (
      <main className="public-main login-main">
        <section className="login-card">
          <h1>페이지를 찾을 수 없습니다.</h1>
          <p className="login-description">요청한 GuildUp 화면을 찾을 수 없습니다.</p>
          <a href="/communities.html">내 커뮤니티로 돌아가기</a>
        </section>
      </main>
    );
  }

  return <Page />;
}
