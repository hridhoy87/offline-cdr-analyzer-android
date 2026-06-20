import os
import json
import time
import re

def generate_case_report_html(case_name, summary_html, graph_json_str, same_loc_json, 
                            alias_database=None, location_request="N/A", timeline_analysis="N/A", 
                            cdr_names=None, spatial_summary=None):
    """Generates a professional forensic PDF report with margins, headers, and footers."""
    try:
        # 1. Data Ingestion
        try:
            main_data = json.loads(str(graph_json_str)) if graph_json_str else {}
        except:
            main_data = {}

        preview_rows = main_data.get("preview_rows", [])
        common_links_list = main_data.get("common-links", [])
        node_profiles = main_data.get("node_profiles", [])
        sim_to_imei_map = main_data.get("sim_to_imei_map", {})
        imei_to_sim_map = main_data.get("imei_to_sim_map", {})
        hourly_activity = main_data.get("hourly_activity", {})
        alias_db = main_data.get("alias_map", {})

        # Merge passed alias database if any
        if alias_database:
            try:
                for k in alias_database:
                    alias_db[str(k)] = str(alias_database[k])
            except: pass
        
        # Format CDR Sources
        py_cdrs = []
        if cdr_names:
            try:
                for name in cdr_names: py_cdrs.append(str(name))
            except: py_cdrs = ["Evidence Block"]
        else: py_cdrs = ["N/A"]

        # Helper functions
        def format_with_alias_html(val):
            s_val = str(val).strip()
            if "(📌" in s_val: return f"<b>{s_val}</b>"
            if s_val in alias_db: return f"<b>{s_val}(📌 {alias_db[s_val]})</b>"
            return s_val

        def format_bold_aliases(text):
            if not text: return ""
            t = str(text).replace('<br/>', '\n').replace('<br>', '\n')
            sorted_nums = sorted(alias_db.keys(), key=len, reverse=True)
            for num in sorted_nums:
                pattern = re.compile(re.escape(num))
                t = pattern.sub(f"<b>{num}(📌 {alias_db[num]})</b>", t)
            return t.replace('\n', '<br/>')

        def get_color(ratio):
            def interpolate(c1, c2, f):
                r1, g1, b1 = (c1 >> 16) & 0xFF, (c1 >> 8) & 0xFF, c1 & 0xFF
                r2, g2, b2 = (c2 >> 16) & 0xFF, (c2 >> 8) & 0xFF, c2 & 0xFF
                return int(r1 + (r2 - r1) * f), int(g1 + (g2 - g1) * f), int(b1 + (b2 - b1) * f)
            if ratio <= 0: return "#EDF2F7"
            if ratio < 0.5: r, g, b = interpolate(0x27AE60, 0xF1C40F, ratio * 2)
            else: r, g, b = interpolate(0xF1C40F, 0xE74C3C, (ratio - 0.5) * 2)
            return f"rgb({r},{g},{b})"

        # Content Generation
        heatmap_html = ""
        if hourly_activity:
            heatmap_html += "<h2 class='section-title'>⏰ Temporal Activity Patterns</h2>"
            for a_p, dist in hourly_activity.items():
                vals = list(dist.values())
                max_val = max(vals) if vals else 0
                heatmap_html += f"<div class='heatmap-row'><h3>Subscriber: {format_with_alias_html(a_p)}</h3>"
                heatmap_html += "<div class='heatmap-bar'>"
                for h in range(24):
                    count = dist.get(str(h), 0)
                    ratio = count / max_val if max_val > 0 else 0
                    heatmap_html += f"<div style='background:{get_color(ratio)};'></div>"
                heatmap_html += "</div><div class='heatmap-labels'><span>00</span><span>06</span><span>12</span><span>18</span><span>23</span></div></div>"

        link_html = ""
        if common_links_list:
            link_html += "<h2 class='section-title'>🧬 Link Correlation Insights</h2><table class='forensic-table'><thead><tr><th>Sl</th><th>Shared Common Contact</th><th>Connected Targets</th><th>Hits</th></tr></thead><tbody>"
            for idx, item in enumerate(common_links_list, 1):
                cbp, aps = item.get("target"), item.get("source", [])
                hits = node_profiles.get(str(cbp), {}).get("total", "N/A")
                aps_str = ", ".join([format_with_alias_html(a) for a in aps])
                link_html += f"<tr><td>{idx}</td><td>{format_with_alias_html(cbp)}</td><td>{aps_str}</td><td><b>{hits}</b></td></tr>"
            link_html += "</tbody></table>"

        device_html = ""
        if sim_to_imei_map or imei_to_sim_map:
            device_html += "<h2 class='section-title'>📱 Device Mapping Profiles</h2>"
            if sim_to_imei_map:
                device_html += "<h3>📡 Subscriber SIM Index</h3><table class='forensic-table'><thead><tr><th>SIM</th><th>Associated IMEIs</th></tr></thead><tbody>"
                for sim, recs in sim_to_imei_map.items():
                    imei_rows = []
                    for r in recs:
                        imei_rows.append(f"• {format_with_alias_html(r.get('imei'))} ({r.get('hw')})")
                    device_html += f"<tr><td>{format_with_alias_html(sim)}</td><td>{'<br/>'.join(imei_rows)}</td></tr>"
                device_html += "</tbody></table>"
            if imei_to_sim_map:
                device_html += "<h3>🛡️ Handset Core Index</h3><table class='forensic-table'><thead><tr><th>Model</th><th>IMEI</th><th>Linked SIMs</th></tr></thead><tbody>"
                for imei, info in imei_to_sim_map.items():
                    sims_str = ", ".join([format_with_alias_html(s) for s in info.get("sims", [])])
                    device_html += f"<tr><td><b>{info.get('hardware')}</b></td><td>{format_with_alias_html(imei)}</td><td>{sims_str}</td></tr>"
                device_html += "</tbody></table>"

        logs_html = "<h2 class='section-title'>👁️ Telemetry Logs Preview</h2><table class='forensic-table'><thead><tr><th>Time</th><th>Ap</th><th>Bp</th><th>Freq</th><th>BTS Address</th></tr></thead><tbody>"
        if preview_rows:
            for r in preview_rows[:100]:
                logs_html += f"<tr><td>{r.get('dt')}</td><td>{format_with_alias_html(r.get('ap'))}</td><td>{format_with_alias_html(r.get('bp'))}</td><td>{r.get('freq')}</td><td>{r.get('loc')}</td></tr>"
        else: logs_html += "<tr><td colspan='5'>No records found.</td></tr>"
        logs_html += "</tbody></table>"

        spatial_html = "<h2 class='section-title'>📍 Spatial Cross-Over Interceptions</h2>"
        if spatial_summary:
            spatial_html += f"<div class='crisp-summary'><b>Summary Matrix:</b><br/>{format_bold_aliases(spatial_summary)}</div>"
        
        try:
            sl_raw = json.loads(str(same_loc_json)) if same_loc_json else []
            if isinstance(sl_raw, str): sl_raw = json.loads(sl_raw)
        except: sl_raw = []
        
        if sl_raw:
            spatial_html += "<table class='forensic-table'><thead><tr><th>Time</th><th>Ap</th><th>Bp</th><th>LAC</th><th>Cell</th><th>Address</th><th>Reason</th></tr></thead><tbody>"
            for r in sl_raw[:500]:
                spatial_html += f"<tr><td>{r.get('Time')}</td><td>{format_with_alias_html(r.get('A_Party'))}</td><td>{format_with_alias_html(r.get('B_Party'))}</td><td>{r.get('LAC')}</td><td>{r.get('Cell')}</td><td>{r.get('BTS_Loc')}</td><td>{r.get('Reason')}</td></tr>"
            spatial_html += "</tbody></table>"
        else: spatial_html += "<p><i>No overlaps detected.</i></p>"

        # Template
        html = f"""
        <!DOCTYPE html><html><head><style>
            @page {{ size: A4; margin: 2.2cm 1.5cm 1.8cm 1.5cm; }}
            body {{ font-family: 'Helvetica', 'Arial', sans-serif; font-size: 9pt; color: #111; line-height: 1.4; margin: 0; }}
            .fixed-header {{ position: fixed; top: -1.5cm; left: 0; right: 0; text-align: center; font-weight: bold; border-bottom: 0.5pt solid #000; padding-bottom: 5pt; font-size: 10pt; }}
            .fixed-footer {{ position: fixed; bottom: -1cm; left: 0; right: 0; text-align: center; font-weight: bold; border-top: 0.5pt solid #000; padding-top: 5pt; font-size: 8pt; }}
            .title-box {{ text-align: center; border-bottom: 2pt solid #000; padding-bottom: 12pt; margin-bottom: 15pt; }}
            .title-box h1 {{ font-size: 20pt; margin: 0; letter-spacing: 1pt; }}
            .case-badge {{ font-size: 13pt; font-weight: bold; margin-top: 6pt; color: #333; }}
            .metadata-table {{ width: 100%; font-size: 8.5pt; margin: 10pt 0; border: 0.5pt dashed #aaa; padding: 6pt; }}
            .section-title {{ font-size: 14pt; border-bottom: 1.2pt solid #000; margin-top: 25pt; padding-bottom: 4pt; text-transform: uppercase; page-break-after: avoid; }}
            .forensic-table {{ width: 100%; border-collapse: collapse; margin-top: 8pt; page-break-inside: auto; }}
            .forensic-table th, .forensic-table td {{ border: 0.5pt solid #000; padding: 5pt; text-align: left; font-size: 8pt; }}
            .forensic-table th {{ background-color: #e5e5e5; font-weight: bold; }}
            .forensic-table tr {{ page-break-inside: avoid; }}
            .heatmap-row {{ margin-bottom: 15pt; border: 0.5pt solid #eee; padding: 8pt; page-break-inside: avoid; }}
            .heatmap-row h3 {{ font-size: 9.5pt; margin: 0 0 6pt 0; border-bottom: 0.5pt solid #ddd; padding-bottom: 3pt; }}
            .heatmap-bar {{ display: flex; height: 16pt; border: 0.5pt solid #999; background: #fff; }}
            .heatmap-bar div {{ flex: 1; border-right: 0.1pt solid #fff; }}
            .heatmap-labels {{ display: flex; justify-content: space-between; font-size: 7pt; color: #666; margin-top: 3pt; }}
            .crisp-summary {{ font-size: 9pt; background: #f2f7ff; padding: 12pt; border-left: 4pt solid #3182CE; margin-bottom: 15pt; white-space: pre-wrap; }}
            h3 {{ font-size: 10.5pt; margin-top: 15pt; text-transform: uppercase; color: #222; }}
        </style></head><body>
            <div class="fixed-header">SECRET // FORENSIC CDR INTELLIGENCE BRIEF</div>
            <div class="fixed-footer">CLASSIFICATION: SECRET // AUTHORIZED USE ONLY // GENERATED: {time.strftime('%Y-%m-%d')}</div>
            <div class="title-box">
                <h1>📡 TELEMETRY INTELLIGENCE BRIEF</h1>
                <div class="case-badge">CASE: {str(case_name).upper()}</div>
                <div style="font-size:8pt; margin-top:4pt;">Analysis Run: {time.strftime('%Y-%m-%d %H:%M:%S')}</div>
            </div>
            <div class="metadata-table">
                Scope: {str(location_request)} | Window: {str(timeline_analysis)}<br/>
                Sources: {", ".join(py_cdrs)}
            </div>
            <h2 class="section-title">🎯 Operational Summary</h2>
            <div style="padding: 10pt; border: 0.5pt solid #eee;">{format_bold_aliases(summary_html)}</div>
            {heatmap_html}{link_html}{device_html}{logs_html}{spatial_html}
            <div style="text-align:center; font-size:8pt; margin-top:40pt; color:#aaa;">*** END OF INTELLIGENCE BRIEF ***</div>
        </body></html>
        """
        return html
    except Exception as e:
        return f"<html><body><h1 style='color:red;'>System Error</h1><p>{str(e)}</p></body></html>"
