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
        except Exception:
            main_data = {}

        preview_rows = main_data.get("preview_rows", [])
        common_links_list = main_data.get("common-links", [])
        node_profiles = main_data.get("node_profiles", {})
        if not isinstance(node_profiles, dict):
            node_profiles = {}
        sim_to_imei_map = main_data.get("sim_to_imei_map", {})
        imei_to_sim_map = main_data.get("imei_to_sim_map", {})
        hourly_activity = main_data.get("hourly_activity", {})
        
        # Format CDR Sources
        py_cdrs = []
        if cdr_names:
            try:
                for name in cdr_names:
                    py_cdrs.append(str(name))
            except Exception:
                py_cdrs = ["Evidence Block"]
        else:
            py_cdrs = ["N/A"]

        def get_color(ratio):
            def interpolate(c1, c2, f):
                r1, g1, b1 = (c1 >> 16) & 0xFF, (c1 >> 8) & 0xFF, c1 & 0xFF
                r2, g2, b2 = (c2 >> 16) & 0xFF, (c2 >> 8) & 0xFF, c2 & 0xFF
                return int(r1 + (r2 - r1) * f), int(g1 + (g2 - g1) * f), int(b1 + (b2 - b1) * f)
            if ratio <= 0:
                return "#EDF2F7"
            if ratio < 0.5:
                r, g, b = interpolate(0x27AE60, 0xF1C40F, ratio * 2)
            else:
                r, g, b = interpolate(0xF1C40F, 0xE74C3C, (ratio - 0.5) * 2)
            return f"rgb({r},{g},{b})"

        # Content Generation
        heatmap_html = ""
        if hourly_activity:
            heatmap_html += "<h2 class='section-title'>⏰ Temporal Activity Patterns</h2>"
            for a_p, dist in hourly_activity.items():
                vals = list(dist.values())
                max_val = max(vals) if vals else 0
                heatmap_html += f"<div class='heatmap-row'><h3>Subscriber: {str(a_p)}</h3>"
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
                cbp = item.get("target")
                aps = item.get("source", [])
                hits = node_profiles.get(str(cbp), {}).get("total", "N/A")
                aps_str = ", ".join([str(a) for a in aps])
                link_html += f"<tr><td>{idx}</td><td>{str(cbp)}</td><td>{aps_str}</td><td><b>{hits}</b></td></tr>"
            link_html += "</tbody></table>"

        device_html = ""
        if sim_to_imei_map or imei_to_sim_map:
            device_html += "<h2 class='section-title'>📱 Device Mapping Profiles</h2>"
            if sim_to_imei_map:
                device_html += "<h3>📡 Subscriber SIM Index</h3><table class='forensic-table'><thead><tr><th>SIM</th><th>Associated IMEIs</th></tr></thead><tbody>"
                for sim, recs in sim_to_imei_map.items():
                    imei_rows = []
                    for r in recs:
                        imei_rows.append(f"• {str(r.get('imei'))} ({str(r.get('hw'))})")
                    device_html += f"<tr><td>{str(sim)}</td><td>{'<br/>'.join(imei_rows)}</td></tr>"
                device_html += "</tbody></table>"
            if imei_to_sim_map:
                device_html += "<h3>🛡️ Handset Core Index</h3><table class='forensic-table'><thead><tr><th>Model</th><th>IMEI</th><th>Linked SIMs</th></tr></thead><tbody>"
                for imei, info in imei_to_sim_map.items():
                    sims_str = ", ".join([str(s) for s in info.get("sims", [])])
                    device_html += f"<tr><td><b>{info.get('hardware')}</b></td><td>{str(imei)}</td><td>{sims_str}</td></tr>"
                device_html += "</tbody></table>"

        logs_html = "<h2 class='section-title'>👁️ Telemetry Logs Preview</h2><table class='forensic-table'><thead><tr><th>Time</th><th>Ap</th><th>Bp</th><th>Freq</th><th>BTS Address</th></tr></thead><tbody>"
        if preview_rows:
            for r in preview_rows[:100]:
                logs_html += f"<tr><td>{r.get('dt')}</td><td>{str(r.get('ap'))}</td><td>{str(r.get('bp'))}</td><td>{r.get('freq')}</td><td>{r.get('loc')}</td></tr>"
        else:
            logs_html += "<tr><td colspan='5'>No records found.</td></tr>"
        logs_html += "</tbody></table>"

        spatial_html = "<h2 class='section-title'>📍 Co-Location & Spatial Cross-Over Interceptions</h2>"
        if spatial_summary:
            spatial_html += f"<div class='crisp-summary'><b>Summary Matrix:</b><br/>{str(spatial_summary)}</div>"
        
        try:
            sl_raw = json.loads(str(same_loc_json)) if same_loc_json else []
            if isinstance(sl_raw, str):
                sl_raw = json.loads(sl_raw)
        except Exception:
            sl_raw = []
        
        if sl_raw:
            spatial_html += "<table class='forensic-table'><thead><tr><th>Date</th><th>Time Window</th><th>BTS Address</th><th>LAC</th><th>Cell</th><th>Co-Located A-Parties</th><th>Reason</th></tr></thead><tbody>"
            for r in sl_raw[:500]:
                date_val = r.get('Date', '')
                time_win = r.get('Time_Window', r.get('Time', ''))
                bts_val = str(r.get('BTS_Loc', ''))
                lac_val = str(r.get('LAC', ''))
                cell_val = str(r.get('Cell', ''))
                aps_val = str(r.get('A_Parties', r.get('A_Party', '')))
                reason_val = str(r.get('Reason', 'Exact Sector Match'))
                spatial_html += f"<tr><td>{date_val}</td><td>{time_win}</td><td>{bts_val}</td><td>{lac_val}</td><td>{cell_val}</td><td><b>{aps_val}</b></td><td>{reason_val}</td></tr>"
            spatial_html += "</tbody></table>"
        else:
            spatial_html += "<p><i>No co-locations detected across analyzed targets.</i></p>"

        # Template
        html = f"""
        <!DOCTYPE html><html><head><style>
            @page {{ size: A4; margin: 1.2cm 1.0cm 1.2cm 1.0cm; }}
            body {{ font-family: 'Helvetica', 'Arial', sans-serif; font-size: 8.5pt; color: #111; line-height: 1.35; margin: 0; }}
            .fixed-header {{ position: fixed; top: -0.85cm; left: 0; right: 0; text-align: center; font-weight: bold; border-bottom: 0.5pt solid #000; padding-bottom: 2pt; font-size: 8pt; }}
            .fixed-footer {{ position: fixed; bottom: -0.85cm; left: 0; right: 0; text-align: center; font-weight: bold; border-top: 0.5pt solid #000; padding-top: 2pt; font-size: 7.5pt; }}
            .title-box {{ text-align: center; border-bottom: 2pt solid #000; padding-bottom: 8pt; margin-bottom: 10pt; }}
            .title-box h1 {{ font-size: 18pt; margin: 0; letter-spacing: 0.5pt; }}
            .case-badge {{ font-size: 11pt; font-weight: bold; margin-top: 4pt; color: #333; }}
            .metadata-table {{ width: 100%; font-size: 8pt; margin: 8pt 0; border: 0.5pt dashed #aaa; padding: 5pt; }}
            .section-title {{ font-size: 12pt; border-bottom: 1pt solid #000; margin-top: 16pt; padding-bottom: 3pt; text-transform: uppercase; page-break-after: avoid; }}
            .forensic-table {{ width: 100%; border-collapse: collapse; margin-top: 6pt; page-break-inside: auto; }}
            .forensic-table thead {{ display: table-header-group; }}
            .forensic-table tfoot {{ display: table-footer-group; }}
            .forensic-table th, .forensic-table td {{ border: 0.5pt solid #000; padding: 4pt 5pt; text-align: left; font-size: 7.5pt; word-break: break-word; }}
            .forensic-table th {{ background-color: #e5e5e5; font-weight: bold; }}
            .forensic-table tr {{ page-break-inside: avoid; page-break-after: auto; }}
            .heatmap-row {{ margin-bottom: 10pt; border: 0.5pt solid #eee; padding: 6pt; page-break-inside: avoid; }}
            .heatmap-row h3 {{ font-size: 8.5pt; margin: 0 0 4pt 0; border-bottom: 0.5pt solid #ddd; padding-bottom: 2pt; }}
            .heatmap-bar {{ display: flex; height: 14pt; border: 0.5pt solid #999; background: #fff; }}
            .heatmap-bar div {{ flex: 1; border-right: 0.1pt solid #fff; }}
            .heatmap-labels {{ display: flex; justify-content: space-between; font-size: 6.5pt; color: #666; margin-top: 2pt; }}
            .crisp-summary {{ font-size: 8.5pt; background: #f2f7ff; padding: 8pt; border-left: 3pt solid #3182CE; margin-bottom: 10pt; white-space: pre-wrap; }}
            h3 {{ font-size: 9.5pt; margin-top: 10pt; text-transform: uppercase; color: #222; }}
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
            <div style="padding: 10pt; border: 0.5pt solid #eee;">{summary_html}</div>
            {heatmap_html}{link_html}{device_html}{logs_html}{spatial_html}
            <div style="text-align:center; font-size:8pt; margin-top:40pt; color:#aaa;">*** END OF INTELLIGENCE BRIEF ***</div>
        </body></html>
        """
        return html
    except Exception as e:
        return f"<html><body><h1 style='color:red;'>System Error</h1><p>{str(e)}</p></body></html>"
