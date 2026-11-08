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
