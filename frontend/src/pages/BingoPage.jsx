import { useCallback, useEffect, useMemo, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";
import TemporaryBingoRebuildPanel from "../components/TemporaryBingoRebuildPanel.jsx";
import { useCommunity } from "../community/CommunityContext.jsx";
import { canManageCommunity } from "../communityAccess.js";
import { bingoAggregationCooldown, bingoParticipantNames, currentBingoScreen, formatBingoKstDateTime, formatBingoLocalDateTime, groupManagedBingos, isBingoManagementView } from "../bingoView.js";

const MISSION_GROUPS = [
 ["전투",[["KILLS","킬"],["DAMAGE_DEALT","딜량"],["ASSISTS","어시스트"],["DBNOS","기절"],
  ["HEADSHOT_KILLS","헤드샷 킬"],["LONG_DISTANCE_KILL","장거리 킬"],["WEAPON_KILLS","특정 무기 킬"],
  ["WEAPON_CATEGORY_KILLS","무기 종류 킬"],["THROWABLE_KILLS","투척물 킬"],["ROAD_KILLS","로드킬"],
  ["WALL_PENETRATION_KILLS","벽 관통 킬"],["ARMOR_DESTROY_COUNT","방어구 파괴"],["VEHICLE_DAMAGE","차량 피해량"],["VEHICLE_DESTROY_COUNT","차량 파괴"]]],
 ["경기 결과",[["WINS","치킨"],["TOP10","Top 10"],["SURVIVAL_TIME","생존 시간"],["MATCHES_PLAYED","경기 참여"]]],
 ["아이템·행동",[["HEALS","회복 아이템 사용"],["BOOSTS","부스트 아이템 사용"],["REVIVES","팀원 부활"],["CARRY","다운된 팀원 업기"],["THROWABLE_USED","투척물 사용"],
  ["CARE_PACKAGE_PICKUP","보급상자 아이템 획득"],["FLARE_GUN_USED","플레어건 사용"],
  ["VAULT_COUNT","파쿠르"],["LEDGE_GRAB_COUNT","난간 잡기"],["WHEEL_DESTROY_COUNT","차량 타이어 파괴"],
  ["ITEM_PICKUP","특정 아이템 획득"],["ITEM_USE","특정 아이템 사용"],["ENEMY_LOOTBOX_PICKUP","적 데스박스 루팅"],
  ["EMERGENCY_PICKUP_RIDE","비상호출 탑승"],["BREACHABLE_WALL_DESTROY_COUNT","파괴 가능한 벽 파괴"]]],
 ["이동",[["WALK_DISTANCE","도보 이동"],["RIDE_DISTANCE","차량 이동"],["SWIM_DISTANCE","수영"],["PARACHUTE_DISTANCE","낙하산 이동"],["FREEFALL_DISTANCE","자유낙하 이동"]]],
 ["클랜",[["PLAY_WITH_CLAN_MEMBERS","클랜원과 함께 플레이"],["RIDE_WITH_CLAN_MEMBERS","클랜원과 같은 차량 탑승"]]],
 ["GuildUp 콘텐츠",[["KILL_BET_WIN","킬내기 승리"]]],
];
const MISSIONS = MISSION_GROUPS.flatMap(([,missions])=>missions);
const LABELS = Object.fromEntries(MISSIONS);
const CATEGORIES = ["AR","DMR","SR","SMG","Shotgun","LMG","Handgun","Crossbow","Melee"];
const THROWABLES = ["FragGrenade","SmokeBomb","FlashBang","Molotov"];
const KM_MISSIONS = new Set(["WALK_DISTANCE","RIDE_DISTANCE","PARACHUTE_DISTANCE","FREEFALL_DISTANCE"]);
const EMPTY_CATALOG = {items:[],maps:{},weapons:[]};
const emptyCell = (position) => ({ position, missionType:"KILLS", aggregationType:"EVENT_TOTAL", operator:"GREATER_THAN_OR_EQUAL", targetValue:1, occurrenceTarget:null, options:{}, customTitle:"" });
const kst = formatBingoKstDateTime;
const instant = (value) => value ? new Date(`${value}:00+09:00`).toISOString() : null;
const localKst = (value) => value ? new Date(new Date(value).getTime()+9*3600000).toISOString().slice(0,16) : "";
const remaining = (value) => {const ms=new Date(value).getTime()-Date.now();if(ms<=0)return "종료됨";const hours=Math.ceil(ms/3600000);return hours>=24?`${Math.floor(hours/24)}일 ${hours%24}시간`:`${hours}시간`;};

export default function BingoPage() {
  const { community } = useCommunity();
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const communityGameId = new URLSearchParams(window.location.search).get("communityGameId");
  const bingoApi = `/api/communities/${encodeURIComponent(communityId)}/games/${encodeURIComponent(communityGameId)}/bingos`;
  const admin = canManageCommunity(community?.role);
  const managing = isBingoManagementView(community?.role, window.location.search);
  const [items,setItems]=useState([]), [selected,setSelected]=useState(null), [loading,setLoading]=useState(true), [catalog,setCatalog]=useState(EMPTY_CATALOG);
  const [currentType,setCurrentType]=useState("NONE");
  const [message,setMessage]=useState(""), [editing,setEditing]=useState(false), [cellModal,setCellModal]=useState(null);
  const [editingId,setEditingId]=useState(null);
  const [viewedBoard,setViewedBoard]=useState(null);
  const [aggregationJob,setAggregationJob]=useState(null);
  const [personalAggregationJob,setPersonalAggregationJob]=useState(null);
  const [form,setForm]=useState(() => initialForm()); const [completions,setCompletions]=useState([]);

  const load = useCallback(async () => {
    setLoading(true); setMessage("");
    try {
      // The catalog only enriches the mission editor. A rolling deployment or
      // a backend process that has not restarted yet must not break bingo view.
      const catalogRequest=api(`${bingoApi}/catalog`).catch(()=>EMPTY_CATALOG);
      if (managing) {
        const [catalogData,bingos]=await Promise.all([catalogRequest,api(bingoApi)]);
        setCatalog(catalogData); setItems(bingos);
        setCurrentType("NONE");
      } else {
        const [catalogData,current]=await Promise.all([catalogRequest,api(`${bingoApi}/current`)]);
        setCatalog(catalogData);
        setCurrentType(current.type);
        setSelected(current.bingo);
        setItems([]);
      }
    }
    catch (error) { if (!redirectToLogin(error)) setMessage(error.message); }
    finally { setLoading(false); }
  },[bingoApi,managing]);
  useEffect(()=>{ load(); },[load]);

  useEffect(()=>{
    if(!admin||!managing||!selected?.id){setAggregationJob(null);return undefined;}
    let cancelled=false,timer;
    const loadStatus=()=>api(`${bingoApi}/${selected.id}/aggregate/status`)
      .then(job=>{if(!cancelled)setAggregationJob(job);})
      .catch(error=>{if(!cancelled&&!redirectToLogin(error)){setMessage("집계 상태 확인을 다시 시도하고 있습니다.");timer=window.setTimeout(loadStatus,3000);}});
    loadStatus();
    return()=>{cancelled=true;window.clearTimeout(timer);};
  },[admin,managing,selected?.id,bingoApi]);

  useEffect(()=>{
    if(!selected?.id||!selected?.me||!["ACTIVE","SETTLING"].includes(selected.status)){setPersonalAggregationJob(null);return undefined;}
    let cancelled=false,timer;
    const loadStatus=()=>api(`${bingoApi}/${selected.id}/aggregate/me/status`)
      .then(job=>{if(!cancelled)setPersonalAggregationJob(job);})
      .catch(error=>{if(!cancelled&&!redirectToLogin(error)){setMessage("업데이트 상태 확인을 다시 시도하고 있습니다.");timer=window.setTimeout(loadStatus,3000);}});
    loadStatus();
    return()=>{cancelled=true;window.clearTimeout(timer);};
  },[selected?.id,selected?.me?.participantId,selected?.status,bingoApi]);

  useEffect(()=>{
    if(aggregationJob?.state!=="RUNNING"||aggregationJob.bingoId!==selected?.id)return undefined;
    let cancelled=false, timer;
    const poll=async()=>{
      try{
        const job=await api(`${bingoApi}/${selected.id}/aggregate/status`);
        if(cancelled)return;
        if(job.state==="RUNNING"){
          setAggregationJob(job);
          timer=window.setTimeout(poll,2000);
        }
        else if(job.state==="SUCCEEDED"||job.state==="COMPLETED_WITH_WARNINGS"){
          await Promise.all([open(selected.id),load()]);
          if(!cancelled){
            setMessage(job.state==="COMPLETED_WITH_WARNINGS"?(job.message||`집계는 완료됐지만 ${job.telemetryFailures??0}개 경기는 다음에 다시 확인합니다.`):`빙고 집계 완료: Match ${job.processedMatches??0}개 · 참가자 ${job.updatedParticipants??0}명 반영`);
            setAggregationJob(job);
          }
        }else if(job.state==="FAILED"){
          setMessage(job.message||"빙고 집계에 실패했습니다.");
          setAggregationJob(job);
        }
      }catch(error){
        if(!cancelled&&!redirectToLogin(error)){
          setMessage("집계는 계속 진행 중입니다. 상태 확인을 다시 시도하고 있습니다.");
          timer=window.setTimeout(poll,3000);
        }
      }
    };
    timer=window.setTimeout(poll,1500);
    return()=>{cancelled=true;window.clearTimeout(timer);};
  },[aggregationJob?.state,aggregationJob?.bingoId,selected?.id,bingoApi,load]);

  useEffect(()=>{
    if(personalAggregationJob?.state!=="RUNNING"||personalAggregationJob.bingoId!==selected?.id)return undefined;
    let cancelled=false, timer;
    const poll=async()=>{
      try{
        const job=await api(`${bingoApi}/${selected.id}/aggregate/me/status`);
        if(cancelled)return;
        if(job.state==="RUNNING"){
          setPersonalAggregationJob(job);
          timer=window.setTimeout(poll,2000);
        }else if(job.state==="SUCCEEDED"||job.state==="COMPLETED_WITH_WARNINGS"){
          await Promise.all([open(selected.id),load()]);
          if(!cancelled){
            setMessage(job.state==="COMPLETED_WITH_WARNINGS"?(job.message||`업데이트는 완료됐지만 ${job.telemetryFailures??0}개 경기는 다음에 다시 확인합니다.`):`내 빙고 업데이트 완료: 경기 ${job.processedMatches??0}개 반영`);
            setPersonalAggregationJob(job);
          }
        }else if(job.state==="FAILED"){
          setMessage(job.message||"내 빙고 업데이트에 실패했습니다.");
          setPersonalAggregationJob(job);
        }
      }catch(error){
        if(!cancelled&&!redirectToLogin(error)){
          setMessage("업데이트는 계속 진행 중입니다. 상태 확인을 다시 시도하고 있습니다.");
          timer=window.setTimeout(poll,3000);
        }
      }
    };
    timer=window.setTimeout(poll,1500);
    return()=>{cancelled=true;window.clearTimeout(timer);};
  },[personalAggregationJob?.state,personalAggregationJob?.bingoId,selected?.id,bingoApi,load]);

  async function open(id) {
    try { setSelected(await api(`${bingoApi}/${id}`)); setViewedBoard(null); setEditing(false); }
    catch(error){ setMessage(error.message); }
  }
  function startCreate(){ setForm(initialForm()); setEditingId(null); setSelected(null); setEditing(true); }
  function startEdit(){setForm({title:selected.title,description:selected.description||"",startsAt:localKst(selected.startsAt),endsAt:localKst(selected.endsAt),boardSize:selected.boardSize,targetLines:selected.targetLines,blackoutEnabled:selected.blackoutEnabled,allowLateJoin:selected.allowLateJoin,status:selected.status,locked:selected.status==="ACTIVE",cells:selected.cells.map(cell=>({...cell,configured:true,customTitle:cell.customTitle||"",options:cell.options||{}}))});setEditingId(selected.id);setEditing(true);}
  function resize(size){ setForm(current=>({...current,boardSize:size,cells:Array.from({length:size*size},(_,i)=>current.cells[i]??emptyCell(i))})); }
  async function save(event){
    event.preventDefault(); setMessage("");
    if(form.cells.some(c=>!c.configured)){ setMessage("모든 빙고 칸의 미션을 설정해 주세요."); return; }
    const payload={...form,startsAt:instant(form.startsAt),endsAt:instant(form.endsAt),cells:form.cells.map(cell=>({position:cell.position,missionType:cell.missionType,aggregationType:cell.aggregationType,operator:cell.operator,targetValue:cell.targetValue,occurrenceTarget:cell.occurrenceTarget,options:cell.options||{},customTitle:cell.customTitle||""}))};
    delete payload.locked;
    try { const created=await api(`${bingoApi}${editingId?`/${editingId}`:""}`,{method:editingId?"PATCH":"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(payload)}); setSelected(created);setEditing(false);setEditingId(null);await load(); }
    catch(error){ setMessage(error.message); }
  }
  async function aggregate(){
    setMessage("");
    try{
      const job=await api(`${bingoApi}/${selected.id}/aggregate`,{method:"POST"});
      setAggregationJob(job);
      setMessage("집계를 시작했습니다. PUBG 경기와 Telemetry를 확인하는 동안 상태를 자동으로 갱신합니다.");
    }catch(error){setMessage(error.message);}
  }
  async function aggregateMe(){
    setMessage("");
    try{
      const job=await api(`${bingoApi}/${selected.id}/aggregate/me`,{method:"POST"});
      setPersonalAggregationJob(job);
      setMessage("내 최근 PUBG 경기를 확인하고 있습니다.");
    }catch(error){setMessage(error.message);}
  }
  async function remove(){ if(!window.confirm("이 빙고를 삭제하거나 취소할까요?")) return; try {await api(`${bingoApi}/${selected.id}`,{method:"DELETE"});setSelected(null);await load();}catch(error){setMessage(error.message);} }
  async function showCell(cell){ setCellModal(cell); setCompletions([]); try{setCompletions(await api(`${bingoApi}/${selected.id}/cells/${cell.id}/completions`));}catch(error){setMessage(error.message);} }
  async function viewParticipant(participantId){ try{setViewedBoard(await api(`${bingoApi}/${selected.id}/participants/${participantId}`));}catch(error){setMessage(error.message);} }

  const groups=useMemo(()=>groupManagedBingos(items),[items]);
  const currentScreen=currentBingoScreen(currentType);
  const moveTo=(view)=>window.location.assign(`/bingos.html?communityId=${encodeURIComponent(communityId)}&communityGameId=${encodeURIComponent(communityGameId)}${view?`&view=${view}`:""}`);

  return <DashboardLayout active="bingos" communityId={communityId}>
    <div className="dashboard-content bingo-content">
      <div className="page-heading bingo-heading"><div><p className="eyebrow">PUBG Bingo</p><h1>{managing?"빙고 관리":"빙고"}</h1></div>{admin&&!editing&&(managing?<div className="bingo-heading-actions"><button className="secondary-button" onClick={()=>moveTo(null)}>내 빙고판</button><button onClick={startCreate}><Icon name="plus"/>빙고 생성</button></div>:<button onClick={()=>moveTo("manage")}>빙고 관리</button>)}</div>
      {message&&<p className="message" role="alert">{message}</p>}
      {loading?<section className="panel page-state">빙고를 불러오는 중입니다.</section>:editing?<BingoEditor form={form} setForm={setForm} resize={resize} save={save} cancel={()=>setEditing(false)} editCell={setCellModal}/>:!managing&&currentScreen==="SCHEDULED"&&selected?<ScheduledBingo bingo={selected} showCell={showCell}/>:selected?<><BingoDetail bingo={selected} viewedBoard={viewedBoard} admin={admin&&managing} aggregate={aggregate} aggregationJob={aggregationJob} aggregateMe={aggregateMe} personalAggregationJob={personalAggregationJob} remove={remove} edit={startEdit} back={managing?()=>setSelected(null):null} showCell={showCell} viewParticipant={viewParticipant}/>{admin&&managing&&selected.status==="ACTIVE"&&<TemporaryBingoRebuildPanel bingoApi={bingoApi} onApplied={()=>open(selected.id)}/>}</>:managing?<>
        <BingoSection title="현재 진행 중인 빙고" empty="진행 중인 빙고가 없습니다." items={groups.active} open={open}/>
        <BingoSection title="예정된 빙고" empty="예정된 빙고가 없습니다." items={groups.scheduled} open={open}/>
        <BingoSection title="작성 중인 빙고" empty="작성 중인 빙고가 없습니다." items={groups.draft} open={open}/>
        <BingoSection title="종료된 빙고" empty="종료된 빙고가 없습니다." items={groups.completed} open={open}/>
        <BingoSection title="취소된 빙고" empty="취소된 빙고가 없습니다." items={groups.cancelled} open={open}/>
      </>:<p className="panel empty-state">현재 진행 중인 빙고가 없습니다.</p>}
    </div>
    {cellModal&&(editing?<MissionModal cell={cellModal.cell} index={cellModal.index} catalog={catalog} close={()=>setCellModal(null)} saveCell={(index,cell)=>{setForm(f=>({...f,cells:f.cells.map((x,i)=>i===index?{...cell,position:index,configured:true}:x)}));setCellModal(null);}}/>:<CellDetail bingo={selected} cell={cellModal} completions={completions} close={()=>setCellModal(null)}/>)}
  </DashboardLayout>;
}

function initialForm(){const start=new Date(Date.now()+3600000),end=new Date(Date.now()+86400000);const local=d=>new Date(d.getTime()+9*3600000).toISOString().slice(0,16);return{title:"",description:"",startsAt:local(start),endsAt:local(end),boardSize:3,targetLines:1,blackoutEnabled:false,allowLateJoin:false,status:"SCHEDULED",cells:Array.from({length:9},(_,i)=>emptyCell(i))};}
function BingoSection({title,empty,items,open}){return <section className="bingo-section"><h2>{title}</h2>{items.length===0?<p className="panel empty-state">{empty}</p>:<div className="bingo-event-grid">{items.map(item=><button className="panel bingo-event-card" key={item.id} onClick={()=>open(item.id)}><span className={`bingo-status status-${item.status.toLowerCase()}`}>{item.status}</span><strong>{item.title}</strong><span>{kst(item.startsAt)} ~ {kst(item.endsAt)}</span><small>{item.boardSize}×{item.boardSize} · 목표 {item.targetLines}줄</small>{item.myCompletedCells!=null&&<b>{item.myCompletedCells}/{item.boardSize*item.boardSize} 완료 · {item.myLineCount}줄</b>}</button>)}</div>}</section>}
function ScheduledBingo({bingo,showCell}){const startsIn=remaining(bingo.startsAt);return <><section className="panel bingo-scheduled"><span className="bingo-status status-scheduled">SCHEDULED</span><h2>{bingo.title}</h2><p>{kst(bingo.startsAt)} 시작</p><p>{kst(bingo.endsAt)} 종료</p><dl><div><dt>빙고 크기</dt><dd>{bingo.boardSize} × {bingo.boardSize}</dd></div><div><dt>시작까지</dt><dd>{startsIn==="종료됨"?"곧 시작":startsIn}</dd></div></dl></section><BingoGrid size={bingo.boardSize} cells={bingo.cells} progress={[]} onCell={showCell}/></>}

function BingoEditor({form,setForm,resize,save,cancel,editCell}){return <form className="bingo-editor" onSubmit={save}><section className="panel bingo-form"><label><span>빙고 이름</span><input disabled={form.locked} value={form.title} maxLength="100" required onChange={e=>setForm({...form,title:e.target.value})}/></label><label className="wide"><span>설명</span><textarea value={form.description} onChange={e=>setForm({...form,description:e.target.value})}/></label><label><span>시작 일시 (한국 시간)</span><input disabled={form.locked} type="datetime-local" required value={form.startsAt} onChange={e=>setForm({...form,startsAt:e.target.value})}/><small className="bingo-time-preview">{formatBingoLocalDateTime(form.startsAt)}</small></label><label><span>종료 일시 (한국 시간)</span><input type="datetime-local" required value={form.endsAt} onChange={e=>setForm({...form,endsAt:e.target.value})}/><small className="bingo-time-preview">{formatBingoLocalDateTime(form.endsAt)}</small></label><p className="bingo-time-help wide">오전 12:00은 00:00(자정, 하루 시작), 오후 12:00은 12:00(정오)입니다.</p><label><span>크기</span><select disabled={form.locked} value={form.boardSize} onChange={e=>resize(Number(e.target.value))}>{[3,4,5].map(n=><option key={n} value={n}>{n} × {n}</option>)}</select></label><label><span>목표 줄 수</span><input disabled={form.locked} type="number" min="1" max={form.boardSize*2+2} value={form.targetLines} onChange={e=>setForm({...form,targetLines:Number(e.target.value)})}/></label><label className="check-field"><input disabled={form.locked} type="checkbox" checked={form.blackoutEnabled} onChange={e=>setForm({...form,blackoutEnabled:e.target.checked})}/>블랙빙고 사용</label><label className="check-field"><input disabled={form.locked} type="checkbox" checked={form.allowLateJoin} onChange={e=>setForm({...form,allowLateJoin:e.target.checked})}/>중간 참가 허용</label></section><BingoGrid size={form.boardSize} cells={form.cells} progress={[]} editable={!form.locked} onCell={(cell,index)=>{if(!form.locked)editCell({cell,index})}}/><div className="bingo-form-actions"><button type="button" className="secondary-button" onClick={cancel}>취소</button><button type="submit">저장</button></div></form>}

function MissionModal({cell,index,catalog,close,saveCell}) {
  const [value,setValue]=useState({...cell,options:cell.options||{}});
  const [itemQuery,setItemQuery]=useState("");
  const unitDivisor=KM_MISSIONS.has(value.missionType)?1000:value.missionType==="SURVIVAL_TIME"?60:1;
  const [target,setTarget]=useState(Number(cell.targetValue||1)/(KM_MISSIONS.has(cell.missionType)?1000:cell.missionType==="SURVIVAL_TIME"?60:1));
  const guildUpContent=value.missionType==="KILL_BET_WIN";
  const needsWeapon=value.missionType==="WEAPON_KILLS",allowsKillWeapon=value.missionType==="KILLS",needsCategory=value.missionType==="WEAPON_CATEGORY_KILLS",needsThrowable=["THROWABLE_KILLS","THROWABLE_USED"].includes(value.missionType),needsDistance=value.missionType==="LONG_DISTANCE_KILL",needsClan=["PLAY_WITH_CLAN_MEMBERS","RIDE_WITH_CLAN_MEMBERS"].includes(value.missionType),needsItem=["ITEM_PICKUP","ITEM_USE","CARE_PACKAGE_PICKUP"].includes(value.missionType);
  const itemGroups=Object.entries(catalog.items.filter(item=>(value.missionType!=="ITEM_USE"||item.usable)&&(!itemQuery||`${item.name} ${item.id}`.toLowerCase().includes(itemQuery.toLowerCase()))).reduce((groups,item)=>({...groups,[item.category]:[...(groups[item.category]||[]),item]}),{}));
  const weaponGroups=Object.entries((catalog.weapons||[]).reduce((groups,weapon)=>({...groups,[weapon.category]:[...(groups[weapon.category]||[]),weapon]}),{}));
  const setOption=(name,optionValue)=>setValue(current=>({...current,options:{...current.options,[name]:optionValue}}));
  return <div className="modal-backdrop"><form className="panel bingo-modal" onSubmit={e=>{e.preventDefault();saveCell(index,{...value,targetValue:Number(target)*unitDivisor})}}>
    <h2>미션 설정</h2>
    <label><span>미션 종류</span><select value={value.missionType} onChange={e=>{const missionType=e.target.value;const options={};if(["ITEM_PICKUP","ITEM_USE"].includes(missionType))options.itemId=catalog.items.find(item=>missionType!=="ITEM_USE"||item.usable)?.id||"";if(missionType==="WEAPON_KILLS")options.weapon=catalog.weapons?.[0]?.canonicalName||"";if(missionType==="LONG_DISTANCE_KILL")options.distance=200;if(["PLAY_WITH_CLAN_MEMBERS","RIDE_WITH_CLAN_MEMBERS"].includes(missionType))options.clanMemberCount=1;setValue({...value,missionType,options,aggregationType:missionType==="KILL_BET_WIN"?"EVENT_TOTAL":value.aggregationType,occurrenceTarget:missionType==="KILL_BET_WIN"?null:value.occurrenceTarget});setTarget(1)}}>{MISSION_GROUPS.map(([group,missions])=><optgroup label={group} key={group}>{missions.map(([id,label])=><option value={id} key={id}>{label}</option>)}</optgroup>)}</select></label>
    {guildUpContent?<label><span>집계 방식</span><input value="기간 누적" readOnly/></label>:<label><span>집계 방식</span><select value={value.aggregationType} onChange={e=>setValue({...value,aggregationType:e.target.value,occurrenceTarget:e.target.value==="MATCH_OCCURRENCES"?1:null})}><option value="EVENT_TOTAL">기간 누적</option><option value="SINGLE_MATCH">한 경기</option><option value="MATCH_OCCURRENCES">조건 달성 경기 수</option></select></label>}
    <label><span>{guildUpContent?"목표 승리 횟수":`목표값${KM_MISSIONS.has(value.missionType)?" (km)":value.missionType==="SURVIVAL_TIME"?" (분)":""}`}</span><input type="number" min={guildUpContent?1:0} step={guildUpContent?1:0.1} required value={target} onChange={e=>setTarget(Number(e.target.value))}/></label>
    {value.aggregationType==="MATCH_OCCURRENCES"&&<label><span>달성 경기 수</span><input type="number" min="1" value={value.occurrenceTarget||1} onChange={e=>setValue({...value,occurrenceTarget:Number(e.target.value)})}/></label>}
    {needsWeapon&&<label><span>무기</span><select required value={value.options.weapon||""} onChange={e=>setOption("weapon",e.target.value)}>{weaponGroups.map(([group,weapons])=><optgroup label={group} key={group}>{weapons.map(weapon=><option value={weapon.canonicalName} key={weapon.canonicalName}>{weapon.displayName}</option>)}</optgroup>)}</select></label>}
    {allowsKillWeapon&&<label><span>무기 필터 (선택)</span><select value={value.options.weapon||""} onChange={e=>setOption("weapon",e.target.value)}><option value="">전체</option>{weaponGroups.map(([group,weapons])=><optgroup label={group} key={group}>{weapons.map(weapon=><option value={weapon.canonicalName} key={weapon.canonicalName}>{weapon.displayName}</option>)}</optgroup>)}</select></label>}
    {allowsKillWeapon&&<label><span>무기 종류 필터 (선택)</span><select value={value.options.weaponCategory||""} onChange={e=>setOption("weaponCategory",e.target.value)}><option value="">전체 종류</option>{CATEGORIES.map(x=><option key={x}>{x}</option>)}</select></label>}
    {needsCategory&&<label><span>무기 종류</span><select value={value.options.weaponCategory||"AR"} onChange={e=>setOption("weaponCategory",e.target.value)}>{CATEGORIES.map(x=><option key={x}>{x}</option>)}</select></label>}
    {needsThrowable&&<label><span>투척물</span><select value={value.options.throwable||THROWABLES[0]} onChange={e=>setOption("throwable",e.target.value)}>{THROWABLES.map(x=><option key={x}>{x}</option>)}</select></label>}
    {needsDistance&&<label><span>거리 (m)</span><input type="number" min="1" value={value.options.distance||200} onChange={e=>setOption("distance",Number(e.target.value))}/></label>}
    {needsClan&&<label><span>본인 제외 클랜원 수</span><input type="number" min="1" value={value.options.clanMemberCount||1} onChange={e=>setOption("clanMemberCount",Number(e.target.value))}/></label>}
    {needsItem&&<label><span>아이템 검색</span><input type="search" value={itemQuery} onChange={e=>setItemQuery(e.target.value)} placeholder="이름 또는 PUBG itemId"/></label>}
    {needsItem&&<label><span>아이템{value.missionType==="CARE_PACKAGE_PICKUP"?" (선택)":""}</span><select required={value.missionType!=="CARE_PACKAGE_PICKUP"} value={value.options.itemId||""} onChange={e=>setOption("itemId",e.target.value)}><option value="">전체</option>{itemGroups.map(([group,items])=><optgroup label={group} key={group}>{items.map(item=><option value={item.id} key={item.id}>{item.name}</option>)}</optgroup>)}</select></label>}
    {!guildUpContent&&<label><span>맵 필터 (선택)</span><select value={value.options.map||""} onChange={e=>setOption("map",e.target.value)}><option value="">전체 맵</option>{Object.entries(catalog.maps).map(([id,name])=><option value={id} key={id}>{name}</option>)}</select></label>}
    {!guildUpContent&&<label><span>게임 모드 (선택)</span><select value={value.options.gameMode||""} onChange={e=>setOption("gameMode",e.target.value)}><option value="">전체 모드</option>{["solo","solo-fpp","duo","duo-fpp","squad","squad-fpp"].map(mode=><option key={mode} value={mode}>{mode}</option>)}</select></label>}
    {!guildUpContent&&<label className="check-field"><input type="checkbox" checked={value.options.clanPlayRequired===true} onChange={e=>setOption("clanPlayRequired",e.target.checked)}/>클랜원과 같은 팀으로 플레이한 경기만 기록</label>}
    <label><span>표시 문구 (선택)</span><input maxLength="120" value={value.customTitle||""} onChange={e=>setValue({...value,customTitle:e.target.value})}/></label>
    <div className="bingo-modal-actions"><button type="button" className="secondary-button" onClick={close}>취소</button><button type="submit">적용</button></div>
  </form></div>;
}

function BingoDetail({bingo,viewedBoard,admin,aggregate,aggregationJob,aggregateMe,personalAggregationJob,remove,edit,back,showCell,viewParticipant}) {
  const board=viewedBoard||bingo.me,progress=board?.progress||[];
  const [now,setNow]=useState(Date.now());
  const cooldown=bingoAggregationCooldown(bingo.lastAggregatedAt,now);
  const personalCooldown=bingoAggregationCooldown(bingo.me?.lastAggregatedAt,now);
  const running=aggregationJob?.bingoId===bingo.id&&aggregationJob.state==="RUNNING";
  const personalRunning=personalAggregationJob?.bingoId===bingo.id&&personalAggregationJob.state==="RUNNING";
  const needsPubg=bingo.cells.some(cell=>cell.missionType!=="KILL_BET_WIN");
  useEffect(()=>{
    if(!((admin&&cooldown.disabled)||(bingo.me&&personalCooldown.disabled)))return undefined;
    const timer=window.setInterval(()=>setNow(Date.now()),1000);
    return()=>window.clearInterval(timer);
  },[admin,cooldown.disabled,bingo.lastAggregatedAt,bingo.me,personalCooldown.disabled,bingo.me?.lastAggregatedAt]);
  return <>
    {(back||admin)&&<>
      <div className="bingo-detail-actions">
        {back&&<button className="secondary-button" onClick={back}>목록</button>}
        {admin&&!["COMPLETED","CANCELLED","SETTLING"].includes(bingo.status)&&<button className="secondary-button" onClick={edit}>수정</button>}
        {admin&&["ACTIVE","SETTLING"].includes(bingo.status)&&<button onClick={aggregate} disabled={running||cooldown.disabled} title={running?"현재 전체 집계가 진행 중입니다.":cooldown.disabled?"마지막 전체 집계 후 30분이 지나면 다시 집계할 수 있습니다.":undefined}>{running?"전체 집계 중…":"전체 집계"}</button>}
        {admin&&<button className="danger-button" onClick={remove}>{["ACTIVE","SETTLING"].includes(bingo.status)?"취소":"삭제"}</button>}
      </div>
      {admin&&<div className="bingo-aggregation-status">
        <span>마지막 성공 집계: <strong>{bingo.lastAggregatedAt?kst(bingo.lastAggregatedAt):"아직 없음"}</strong></span>
        {cooldown.nextAvailableAt&&<span>다음 집계 가능: <strong>{kst(cooldown.nextAvailableAt)}</strong></span>}
        {running&&<span>현재 집계 시작: <strong>{kst(aggregationJob.startedAt||aggregationJob.requestedAt)}</strong></span>}
        {running&&aggregationJob.message&&<span>진행 단계: <strong>{aggregationJob.message}</strong></span>}
        {aggregationJob?.state==="COMPLETED_WITH_WARNINGS"&&<span>재시도 예정 경기: <strong>{aggregationJob.telemetryFailures??0}개</strong></span>}
        {aggregationJob?.state==="FAILED"&&aggregationJob.finishedAt&&<span>마지막 집계 시도 실패: <strong>{kst(aggregationJob.finishedAt)}</strong></span>}
      </div>}
    </>}
    {bingo.me&&["ACTIVE","SETTLING"].includes(bingo.status)&&<section className={`panel bingo-personal-aggregation${personalRunning?" is-running":""}`} aria-busy={personalRunning}>
      <div className="bingo-personal-summary">
        <span className="bingo-personal-icon"><Icon name="bingo" size={22}/></span>
        <div className="bingo-personal-copy">
          <p className="eyebrow">내 빙고</p>
          <div className="bingo-personal-title"><h2>{bingo.me.pubgNickname||bingo.me.nickname}</h2><span className={`bingo-account-badge${bingo.me.pubgConnected?"":" is-disconnected"}`}>{bingo.me.pubgConnected?"PUBG 연결됨":"연결 필요"}</span></div>
          <p>최근 PUBG 기록을 불러와 빙고 진행도를 최신 상태로 업데이트해요.</p>
        </div>
      </div>
      <div className="bingo-personal-dashboard">
        <div className="bingo-personal-metrics">
          <div><span>마지막 업데이트</span><strong>{bingo.me.lastAggregatedAt?kst(bingo.me.lastAggregatedAt):"아직 없음"}</strong></div>
          <div><span>완료 미션</span><strong>{bingo.me.completedCells}<small> / {bingo.boardSize*bingo.boardSize}</small></strong></div>
        </div>
        <div className="bingo-personal-action">
          <p className={`bingo-personal-status${personalRunning?" is-active":""}`} aria-live="polite"><span aria-hidden="true"/>{personalRunning?(personalAggregationJob.message||"최근 경기 확인 중"):!bingo.me.pubgConnected?"PUBG 계정을 연결하면 업데이트할 수 있어요.":personalCooldown.disabled?<>다음 업데이트 <strong>{kst(personalCooldown.nextAvailableAt)}</strong></>:personalAggregationJob?.state==="COMPLETED_WITH_WARNINGS"?<>일부 경기 <strong>{personalAggregationJob.telemetryFailures??0}개</strong>는 다음에 다시 확인해요.</>:personalAggregationJob?.state==="SUCCEEDED"?<>최근 <strong>{personalAggregationJob.processedMatches??0}경기</strong>를 반영했어요.</>:"버튼을 눌러 내 진행도를 새로고침하세요."}</p>
          <button className="bingo-update-button" onClick={aggregateMe} disabled={personalRunning||personalCooldown.disabled||!bingo.me.pubgConnected} title={!bingo.me.pubgConnected?"PUBG 계정을 연결해 주세요.":personalCooldown.disabled?"마지막 업데이트 후 30분이 지나면 다시 업데이트할 수 있습니다.":undefined}><Icon name="refresh" size={17} className={personalRunning?"is-spinning":""}/>{personalRunning?"업데이트 중…":"내 빙고 업데이트"}</button>
        </div>
      </div>
    </section>}
    <section className="panel bingo-overview"><div><span className={`bingo-status status-${bingo.status.toLowerCase()}`}>{bingo.status}</span><h2>{bingo.title}</h2><p>{kst(bingo.startsAt)} ~ {kst(bingo.endsAt)}</p><p>남은 기간 {remaining(bingo.endsAt)}</p><p>일반전 및 경쟁전 경기만 집계됩니다.</p></div><dl><div><dt>완료</dt><dd>{bingo.me?.completedCells??0} / {bingo.boardSize*bingo.boardSize}</dd></div><div><dt>현재</dt><dd>{bingo.me?.lineCount??0}줄 빙고</dd></div><div><dt>목표</dt><dd>{bingo.targetLines}줄</dd></div><div><dt>블랙빙고</dt><dd>{!bingo.blackoutEnabled?"사용 안 함":bingo.me?.blackoutCompletedAt?"달성":"진행 중"}</dd></div></dl></section>
    {bingo.me&&!bingo.me.pubgConnected&&needsPubg&&<p className="message">PUBG 계정을 등록해야 빙고 진행 상황을 집계할 수 있습니다.</p>}
    {viewedBoard&&<p className="bingo-viewing"><strong>{viewedBoard.pubgNickname||viewedBoard.nickname}</strong>님의 빙고판</p>}
    <BingoGrid size={bingo.boardSize} cells={bingo.cells} progress={progress} onCell={showCell}/>
    {bingo.participants.length>0&&<section className="panel bingo-participants"><h2>참가자 달성 현황</h2>{bingo.participants.map(p=>{const names=bingoParticipantNames(p);return <button type="button" key={p.participantId} onClick={()=>viewParticipant(p.participantId)}><span className="bingo-participant-name"><strong>{names.primary}</strong>{names.secondary&&<small>{names.secondary}</small>}</span><span>{p.completedCells}/{bingo.boardSize*bingo.boardSize} · {p.lineCount}줄</span></button>})}</section>}
  </>;
}

function BingoGrid({size,cells,progress,onCell,editable=false}){const byCell=Object.fromEntries(progress.map(p=>[p.cellId,p]));return <div className="bingo-board-scroll"><div className={`bingo-board size-${size}`} style={{gridTemplateColumns:`repeat(${size}, minmax(112px,1fr))`}}>{cells.map((cell,index)=>{const p=byCell[cell.id];return <button type="button" className={`bingo-cell${p?.completed?" is-complete":p&&Number(p.currentValue)>0?" is-progress":""}${!cell.configured&&editable?" is-empty":""}`} key={cell.id??index} onClick={()=>onCell(cell,index)}>{p?.completed&&<Icon name="check" size={20}/>}<strong>{cell.title||cell.customTitle||LABELS[cell.missionType]||"미션 설정"}</strong>{p&&!p.completed&&<small>{cell.aggregationType==="MATCH_OCCURRENCES"?`${p.occurrenceCount} / ${cell.occurrenceTarget}`:`${Number(p.currentValue).toLocaleString()} / ${Number(cell.targetValue).toLocaleString()}`}</small>}</button>})}</div></div>}
function CellDetail({bingo,cell,completions,close}){const p=bingo.me?.progress.find(x=>x.cellId===cell.id);return <div className="modal-backdrop"><section className="panel bingo-modal"><h2>{cell.title}</h2><dl className="bingo-cell-facts"><div><dt>내 진행 상황</dt><dd>{p?`${Number(p.currentValue).toLocaleString()} / ${Number(cell.targetValue).toLocaleString()}${p.completed?" · 완료":""}`:"참가 전"}</dd></div>{p?.completedAt&&<div><dt>달성</dt><dd>{kst(p.completedAt)}</dd></div>}{p?.evidenceMatchId&&<div><dt>달성에 사용된 경기</dt><dd><code>{p.evidenceMatchId}</code><br/>{kst(p.evidenceEventAt)}</dd></div>}</dl><h3>이 미션을 완료한 클랜원</h3>{completions.length===0?<p className="empty-state">아직 완료한 클랜원이 없습니다.</p>:<ul className="bingo-completions">{completions.map((x,i)=><li key={`${x.nickname}-${i}`}><strong>{x.nickname}</strong><span>{kst(x.completedAt)}</span></li>)}</ul>}<button type="button" className="secondary-button" onClick={close}>닫기</button></section></div>}
