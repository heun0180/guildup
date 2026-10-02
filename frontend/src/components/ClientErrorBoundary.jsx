import { Component } from "react";
import { reportClientFailure } from "../api/diagnostics.js";

export default class ClientErrorBoundary extends Component {
  state = { renderFailed: false, asyncFailed: false };

  static getDerivedStateFromError() {
    return { renderFailed: true };
  }

  componentDidCatch(error) {
    reportClientFailure("RENDER_ERROR", error);
  }

  componentDidMount() {
    window.addEventListener("unhandledrejection", this.onRejection);
    window.addEventListener("error", this.onError);
  }

  componentWillUnmount() {
    window.removeEventListener("unhandledrejection", this.onRejection);
    window.removeEventListener("error", this.onError);
  }

  onRejection = (event) => {
    event.preventDefault();
    if (event.reason?.name === "AbortError") return;
    reportClientFailure("UNHANDLED_REJECTION", event.reason);
    this.setState({ asyncFailed: true });
  };

  onError = (event) => {
    if (!event.error) return;
    // Do not allow the browser's default logger to expose arbitrary messages.
    event.preventDefault();
    reportClientFailure("UNEXPECTED_CLIENT_ERROR", event.error);
    this.setState({ asyncFailed: true });
  };

  render() {
    if (this.state.renderFailed) return <main className="public-main login-main">
      <section className="login-card" role="alert">
        <h1>화면을 표시하지 못했습니다.</h1>
        <p className="login-description">잠시 후 새로고침해 주세요. 문제가 계속되면 문의/건의를 통해 알려주세요.</p>
        <button type="button" onClick={() => window.location.reload()}>새로고침</button>
        <p><a href="/communities.html">내 커뮤니티로 돌아가기</a></p>
      </section>
    </main>;
    return <>
      {this.state.asyncFailed && <div className="client-error-notice" role="alert">
        <span>화면 작업 중 오류가 발생했습니다. 현재 화면에서 다시 시도해 주세요.</span>
        <button type="button" className="secondary-button" onClick={() => this.setState({ asyncFailed: false })}>닫기</button>
      </div>}
      {this.props.children}
    </>;
  }
}
