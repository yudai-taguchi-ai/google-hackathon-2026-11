"""気象庁の津波警報（防災情報 XML の VTSE41）から、ある津波予報区の情報を取り出す。"""
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

NS = {
    "jmx": "http://xml.kishou.go.jp/jmaxml1/",
    "ib": "http://xml.kishou.go.jp/jmaxml1/informationBasis1/",
    "seis": "http://xml.kishou.go.jp/jmaxml1/body/seismology1/",
    "eb": "http://xml.kishou.go.jp/jmaxml1/elementBasis1/",
}
SAMPLE = Path(__file__).resolve().parent.parent / "data" / "samples" / "nankai_training_VTSE41.xml"


@dataclass
class TsunamiAlert:
    status: str
    area_name: str
    kind: str
    reported_at: datetime
    arrival_at: datetime | None
    arrival_condition: str | None
    max_height: str | None

    def minutes_left(self, now: datetime | None = None) -> float:
        if self.arrival_at is None:
            return 0.0
        base = now or self.reported_at
        return max(0.0, (self.arrival_at - base).total_seconds() / 60)


def parse(xml_text: str, area_code: str = "510") -> TsunamiAlert | None:
    root = ET.fromstring(xml_text)
    status = root.findtext("jmx:Control/jmx:Status", namespaces=NS)
    reported = root.findtext("ib:Head/ib:ReportDateTime", namespaces=NS)
    for item in root.iterfind("seis:Body/seis:Tsunami/seis:Forecast/seis:Item", NS):
        if item.findtext("seis:Area/seis:Code", namespaces=NS) != area_code:
            continue
        arrival = item.findtext("seis:FirstHeight/seis:ArrivalTime", namespaces=NS)
        height = item.find("seis:MaxHeight/eb:TsunamiHeight", NS)
        return TsunamiAlert(
            status=status,
            area_name=item.findtext("seis:Area/seis:Name", namespaces=NS),
            kind=item.findtext("seis:Category/seis:Kind/seis:Name", namespaces=NS),
            reported_at=datetime.fromisoformat(reported),
            arrival_at=datetime.fromisoformat(arrival) if arrival else None,
            arrival_condition=item.findtext("seis:FirstHeight/seis:Condition", namespaces=NS),
            max_height=(height.get("description") or None) if height is not None else None,
        )
    return None


def training_alert() -> TsunamiAlert:
    return parse(SAMPLE.read_text(encoding="utf-8"))


_replay_started_at: datetime | None = None
# 流し直しは 30 分で自動で終わる（いつまでも「警報中」にならないように）
REPLAY_LASTS = __import__("datetime").timedelta(minutes=30)


def start_replay(now: datetime) -> None:
    """訓練電文を「今」発表されたものとして流し直す。到達までの残り時間は電文と同じにする。"""
    global _replay_started_at
    _replay_started_at = now


def current_alert(now: datetime) -> tuple[TsunamiAlert, bool]:
    """今の警報と、流し直し中かどうか。流し直していない時は電文の発表時刻のまま返す。"""
    a = training_alert()
    if _replay_started_at is None or a.arrival_at is None or now - _replay_started_at > REPLAY_LASTS:
        return a, False
    tz = a.reported_at.tzinfo
    started = _replay_started_at.astimezone(tz)
    lead = a.arrival_at - a.reported_at
    return TsunamiAlert(
        status=a.status,
        area_name=a.area_name,
        kind=a.kind,
        reported_at=started,
        arrival_at=started + lead,
        arrival_condition=a.arrival_condition,
        max_height=a.max_height,
    ), True


# ---- 気象庁の本物の電文（防災情報 XML のフィード。登録不要）----
import time
import urllib.request
import xml.etree.ElementTree as _ET

FEEDS = [
    "https://www.data.jma.go.jp/developer/xml/feed/eqvol.xml",
    "https://www.data.jma.go.jp/developer/xml/feed/eqvol_l.xml",
]
TSUNAMI_TITLE = "津波警報・注意報・予報a"
WARNING_KINDS = ("大津波警報", "津波警報", "津波注意報")
CACHE_SEC = 60
ATOM = {"a": "http://www.w3.org/2005/Atom"}
_live: dict = {"at": 0.0, "value": None}


def _get(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "tsunami-navi-hackathon/0.1"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.read().decode("utf-8")


def latest_tsunami_telegram_url() -> str | None:
    """フィードの中で一番新しい「津波警報・注意報・予報a」の電文の URL。"""
    best = None
    for feed in FEEDS:
        root = _ET.fromstring(_get(feed))
        for e in root.findall("a:entry", ATOM):
            if e.findtext("a:title", namespaces=ATOM) != TSUNAMI_TITLE:
                continue
            updated = e.findtext("a:updated", namespaces=ATOM)
            if best is None or updated > best[0]:
                best = (updated, e.find("a:link", ATOM).get("href"))
    return best[1] if best else None


def live_status(xml_text: str | None, now: datetime, area_code: str = "510") -> dict:
    """一番新しい電文から、大阪府（510）に今、津波の警報・注意報が出ているかを返す。
    訓練・試験の電文、取消、予報（若干の海面変動）、解除は「出ていない」とする。"""
    if xml_text is None:
        return {"active": False, "reason": "no_tsunami_telegram"}
    root = _ET.fromstring(xml_text)
    if root.findtext("jmx:Control/jmx:Status", namespaces=NS) != "通常":
        return {"active": False, "reason": "not_real_telegram"}
    if root.findtext("ib:Head/ib:InfoType", namespaces=NS) == "取消":
        return {"active": False, "reason": "cancelled"}
    a = parse(xml_text, area_code)
    if a is None or not a.kind or "解除" in a.kind or not any(a.kind.startswith(k) for k in WARNING_KINDS):
        return {"active": False, "reason": "no_warning_for_area", "kind": a.kind if a else None}
    arrival = a.arrival_at
    if arrival is None:
        # 「ただちに津波来襲と予測」「第１波の到達を確認」など: 時刻が無い時は今すぐ来るものとして扱う
        arrival = now.astimezone(a.reported_at.tzinfo)
    return {"active": True, "alert": TsunamiAlert(a.status, a.area_name, a.kind, a.reported_at, arrival, a.arrival_condition, a.max_height)}


def live_alert(now: datetime) -> dict:
    """気象庁のフィードを見る（1 分は使い回す）。失敗した時は前回の結果に error を付けて返す。"""
    if time.time() - _live["at"] < CACHE_SEC and _live["value"] is not None:
        return _live["value"]
    try:
        url = latest_tsunami_telegram_url()
        value = live_status(_get(url) if url else None, now)
        value["checked_at"] = now.isoformat()
        value["telegram_url"] = url
    except Exception as e:
        value = dict(_live["value"] or {"active": False}, error=str(e), checked_at=now.isoformat())
    _live.update(at=time.time(), value=value)
    return value
