import LoginPage from "./pages/LoginPage.jsx";
import CommunitiesPage from "./pages/CommunitiesPage.jsx";
import CommunityDashboardPage from "./pages/CommunityDashboardPage.jsx";
import DiscordConnectPage from "./pages/DiscordConnectPage.jsx";
import MembersPage from "./pages/MembersPage.jsx";

const pages = {
  "/": LoginPage,
  "/login.html": LoginPage,
  "/communities.html": CommunitiesPage,
  "/community-dashboard.html": CommunityDashboardPage,
  "/discord-connect.html": DiscordConnectPage,
  "/members.html": MembersPage,
};

export default function App() {
  const Page = pages[window.location.pathname];

  if (!Page) {
    return (
      <main className="page-main compact-main">
        <section className="card">
          <h1>페이지를 찾을 수 없습니다.</h1>
          <a href="/communities.html">내 커뮤니티로 돌아가기</a>
        </section>
      </main>
    );
  }

  return <Page />;
}
