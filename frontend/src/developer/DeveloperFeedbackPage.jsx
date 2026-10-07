import FeedbackHistory from "../components/FeedbackHistory.jsx";

export default function DeveloperFeedbackPage() {
  return <div className="dashboard-content developer-content">
    <header className="page-heading"><p className="eyebrow">GuildUp</p><h1>문의 / 건의 관리</h1>
      <p>서비스 전체 문의를 확인하고 답변과 처리 상태를 관리합니다.</p></header>
    <FeedbackHistory admin />
  </div>;
}
