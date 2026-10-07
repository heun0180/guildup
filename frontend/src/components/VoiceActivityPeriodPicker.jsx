import { useEffect, useId, useRef, useState } from "react";
import Icon from "./Icon.jsx";
import { VoiceActivityPeriod } from "../voiceActivityView.js";
import { addVoiceDays, isCurrentVoicePeriod, parseVoiceDate, shiftVoicePeriod, voiceCalendarDays,
  voiceDate, voiceDateString, voicePeriodStart, voicePeriodTitle } from "../voiceActivityPeriod.js";

const returnLabels = { DAY: "오늘", WEEK: "이번 주", MONTH: "이번 달", YEAR: "올해" };
const pickerLabels = { DAY: "날짜 선택", WEEK: "주 선택", MONTH: "월 선택", YEAR: "연도 선택" };

export default function VoiceActivityPeriodPicker({ period, referenceDate, today, onChange }) {
  const [open, setOpen] = useState(false);
  const [panelDate, setPanelDate] = useState(referenceDate);
  const container = useRef(null);
  const trigger = useRef(null);
  const titleId = useId();
  const current = isCurrentVoicePeriod(period, referenceDate, today);
  const previous = shiftVoicePeriod(period, referenceDate, -1);
  const next = shiftVoicePeriod(period, referenceDate, 1);
  const currentStart = voicePeriodStart(period, today);
  const panel = parseVoiceDate(panelDate ?? referenceDate);
  const year = panel.getUTCFullYear();
  const monthStart = voicePeriodStart(VoiceActivityPeriod.MONTH, panelDate ?? referenceDate);
  const yearBlock = Math.floor((year - 1) / 12) * 12 + 1;

  useEffect(() => { setOpen(false); }, [period, referenceDate]);
  useEffect(() => {
    if (!open) return;
    const closeOutside = (event) => { if (!container.current?.contains(event.target)) setOpen(false); };
    const closeEscape = (event) => {
      if (event.key === "Escape") { setOpen(false); trigger.current?.focus(); }
    };
    document.addEventListener("pointerdown", closeOutside);
    document.addEventListener("keydown", closeEscape);
    container.current?.querySelector('.voice-date-popover [aria-pressed="true"]:not(:disabled)')?.focus();
    return () => {
      document.removeEventListener("pointerdown", closeOutside);
      document.removeEventListener("keydown", closeEscape);
    };
  }, [open]);

  function choose(date) {
    const start = voicePeriodStart(period, date);
    if (!start || start > currentStart) return;
    onChange(start);
    setOpen(false);
    trigger.current?.focus();
  }

  function movePanel(direction) {
    const unit = period === VoiceActivityPeriod.YEAR ? VoiceActivityPeriod.YEAR
      : period === VoiceActivityPeriod.MONTH ? VoiceActivityPeriod.YEAR : VoiceActivityPeriod.MONTH;
    const shifted = shiftVoicePeriod(unit, panelDate, direction * (period === VoiceActivityPeriod.YEAR ? 12 : 1));
    if (shifted) setPanelDate(shifted);
  }

  const panelPrevious = period === VoiceActivityPeriod.YEAR ? yearBlock > 1
    : period === VoiceActivityPeriod.MONTH ? year > 1 : monthStart > "0001-01-01";
  const panelNext = period === VoiceActivityPeriod.YEAR ? yearBlock + 12 <= Number(today.slice(0, 4))
    : period === VoiceActivityPeriod.MONTH ? year < Number(today.slice(0, 4))
      : monthStart < voicePeriodStart(VoiceActivityPeriod.MONTH, today);
  const panelTitle = period === VoiceActivityPeriod.YEAR ? `${yearBlock} ~ ${yearBlock + 11}년`
    : period === VoiceActivityPeriod.MONTH ? `${year}년` : voicePeriodTitle(VoiceActivityPeriod.MONTH, panelDate);

  return <div className="voice-date-controls" ref={container}>
    <div className="voice-date-navigator">
      <button className="voice-date-arrow" type="button" aria-label="이전 기간" disabled={!previous} onClick={() => choose(previous)}><span aria-hidden="true">‹</span></button>
      <button ref={trigger} className="voice-date-trigger" type="button" aria-label={pickerLabels[period]}
        aria-expanded={open} aria-haspopup="dialog" onClick={() => { setPanelDate(referenceDate); setOpen(!open); }}>
        <Icon name="calendar" size={16} /><span>{voicePeriodTitle(period, referenceDate)}</span>
      </button>
      <button className="voice-date-arrow" type="button" aria-label="다음 기간" disabled={!next || next > currentStart} onClick={() => choose(next)}><span aria-hidden="true">›</span></button>
    </div>
    <div className="voice-date-caption">
      <span>{current ? "현재 시각까지 집계" : "선택 기간 전체 집계"}</span>
      {!current && <button type="button" className="voice-period-return" onClick={() => choose(today)}>{returnLabels[period]}<span aria-hidden="true"> ↗</span></button>}
    </div>
    {open && <section className="voice-date-popover" role="dialog" aria-labelledby={titleId}>
      <header>
        <button type="button" className="voice-date-arrow" aria-label="선택 달력 이전" disabled={!panelPrevious} onClick={() => movePanel(-1)}><span aria-hidden="true">‹</span></button>
        <h3 id={titleId}>{panelTitle}</h3>
        <button type="button" className="voice-date-arrow" aria-label="선택 달력 다음" disabled={!panelNext} onClick={() => movePanel(1)}><span aria-hidden="true">›</span></button>
      </header>
      {(period === VoiceActivityPeriod.DAY || period === VoiceActivityPeriod.WEEK) ? <>
        <div className="voice-calendar-weekdays" aria-hidden="true">{["월", "화", "수", "목", "금", "토", "일"].map((day) => <span key={day}>{day}</span>)}</div>
        <div className="voice-calendar-grid">{voiceCalendarDays(panelDate).map((date) => {
          const selected = period === VoiceActivityPeriod.WEEK
            ? date >= referenceDate && date <= addVoiceDays(referenceDate, 6) : date === referenceDate;
          return <button key={date} type="button" aria-label={voicePeriodTitle(VoiceActivityPeriod.DAY, date)}
            aria-pressed={selected} disabled={date > today} onClick={() => choose(date)}
            className={`${date.slice(0, 7) !== panelDate.slice(0, 7) ? "is-muted" : ""}${date === today ? " is-today" : ""}`}>
            {parseVoiceDate(date).getUTCDate()}
          </button>;
        })}</div>
        {period === VoiceActivityPeriod.WEEK && <p>날짜를 선택하면 해당 주 월요일부터 일요일까지 조회합니다.</p>}
      </> : <div className="voice-period-grid">{Array.from({ length: 12 }, (_, index) => {
        const date = voiceDateString(voiceDate(period === VoiceActivityPeriod.MONTH ? year : yearBlock + index,
          period === VoiceActivityPeriod.MONTH ? index + 1 : 1));
        return <button key={date} type="button" disabled={date > currentStart} aria-pressed={date === referenceDate} onClick={() => choose(date)}>
          {period === VoiceActivityPeriod.MONTH ? `${index + 1}월` : `${yearBlock + index}년`}
        </button>;
      })}</div>}
    </section>}
  </div>;
}
