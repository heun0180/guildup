import test from "node:test";
import assert from "node:assert/strict";
import { shiftedRankingQuery } from "../src/rankingView.js";

test("월간 이전/다음 이동은 연도 경계를 처리한다", () => {
  assert.equal(shiftedRankingQuery({ periodType: "MONTHLY", year: 2026, month: 1 }, -1), "year=2025&month=12");
  assert.equal(shiftedRankingQuery({ periodType: "MONTHLY", year: 2025, month: 12 }, 1), "year=2026&month=1");
  assert.equal(shiftedRankingQuery({ periodType: "MONTHLY", year: 2026, month: 9 }, -1), "year=2026&month=8");
});

test("분기 이동은 3개월 단위이며 연도 경계를 처리한다", () => {
  assert.equal(shiftedRankingQuery({ periodType: "QUARTERLY", year: 2026, quarter: 1 }, -1), "year=2025&quarter=4");
  assert.equal(shiftedRankingQuery({ periodType: "QUARTERLY", year: 2025, quarter: 4 }, 1), "year=2026&quarter=1");
  assert.equal(shiftedRankingQuery({ periodType: "QUARTERLY", year: 2026, quarter: 3 }, -1), "year=2026&quarter=2");
});
