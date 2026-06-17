import os
import json
import time

def generate_case_report_html(case_name, summary_html, graph_json_str, same_loc_json, 
                            alias_database=None, location_request="N/A", timeline_analysis="N/A", 
                            cdr_names=None, spatial_summary=None):
    """Generates a polished HTML report for forensic intelligence."""
    try:
        # Load main data from JSON
        try:
            main_data = json.loads(str(graph_json_str)) if graph_json_str else {}
        except:
            main_data = {}

        preview_rows = main_data.get("preview_rows", [])
        common_links_list = main_data.get("common-links", [])
        node_profiles = main_data.get("node_profiles", [])
        sim_to_imei_map = main_data.get("sim_to_imei_map", {})
        imei_to_sim_map = main_data.get("imei_to_sim_map", {})

        # Handle CDR names
        py_cdrs = []
        if cdr_names:
            try:
                for name in cdr_names:
                    py_cdrs.append(str(name))
            except:
                py_cdrs = ["Unknown Sources"]
        else:
            py_cdrs = ["N/A"]

        # Handle alias database
        alias_db = {}
        if alias_database:
            try:
                for k in alias_database:
                    alias_db[str(k)] = str(alias_database[k])
            except:
                pass

        def format_bold_aliases(text):
            if not text: return ""
            t = str(text).replace('📌 ', '').replace('🎯 ', '').replace('🌙 ', '').replace('🔗 ', '')
            t = t.replace('<br/>', '\n').replace('<br>', '\n')
            for num, name in alias_db.items():
                match = f"{name} [{num}]"
                if match in t: t = t.replace(match, f"<b>{match}</b>")
                elif num in t: t = t.replace(num, f"<b>{num}</b>")
            return t.replace('\n', '<br/>')

        # --- SECTION 2: LINK CORRELATION ---
        link_analysis_html = ""
        if not common_links_list:
            link_analysis_html = "<p><i>No cross-link common contacts detected.</i></p>"
        else:
            link_analysis_html = """
            <table class="forensic-table">
                <thead>
                    <tr><th>Sl</th><th>Shared Common Contact</th><th>Connected Targets</th><th>Total Hits</th></tr>
                </thead>
                <tbody>
            """
            for idx, item in enumerate(common_links_list, 1):
                cbp = item.get("target")
                aps = item.get("source", [])
                bolded_aps = []
                for a in aps:
                    raw = str(a).replace('📌 ', '').split(' [')[0].strip() if ' [' in str(a) else str(a).replace('📌 ', '').strip()
                    bolded_aps.append(f"<b>{alias_db[raw]} [{raw}]</b>" if raw in alias_db else str(a))
                
                profile = node_profiles.get(str(cbp), {})
                hits = profile.get("total", "N/A")
                disp = str(cbp)
                for n, name in alias_db.items():
                    if n in disp: disp = f"<b>{name} [{n}]</b>"; break

                link_analysis_html += f"<tr><td>{idx}</td><td>{disp}</td><td>{', '.join(bolded_aps)}</td><td><b>{hits}</b></td></tr>"
            link_analysis_html += "</tbody></table>"

        # --- SECTION 3: DEVICE PROFILES ---
        device_html = ""
        if sim_to_imei_map:
            device_html += "<h2>📡 Subscriber SIM Index</h2><table class=\"forensic-table\"><thead><tr><th>SIM</th><th>Associated IMEIs</th></tr></thead><tbody>"
            for sim, records in sim_to_imei_map.items():
                imeis = "".join([f"• {r.get('imei','N/A')} ({r.get('hw','Generic')})<br/>" for r in records])
                disp = f"<b>{alias_db[sim]} [{sim}]</b>" if sim in alias_db else sim
                device_html += f"<tr><td>{disp}</td><td>{imeis}</td></tr>"
            device_html += "</tbody></table>"
        
        if imei_to_sim_map:
            device_html += "<h2>🛡️ Handset Core Index</h2><table class=\"forensic-table\"><thead><tr><th>Model</th><th>IMEI</th><th>Linked SIMs</th></tr></thead><tbody>"
            for imei, info in imei_to_sim_map.items():
                sims = ", ".join([f"<b>{alias_db[s]} [{s}]</b>" if s in alias_db else s for s in info.get("sims", [])])
                device_html += f"<tr><td><b>{info.get('hardware','Generic')}</b></td><td>{imei}</td><td>{sims}</td></tr>"
            device_html += "</tbody></table>"

        # --- SECTION 4: TELEMETRY LOGS ---
        logs_html = "<table class=\"forensic-table\"><thead><tr><th>Time</th><th>Ap</th><th>Bp</th><th>Freq</th><th>Address</th></tr></thead><tbody>"
        if not preview_rows:
            logs_html += "<tr><td colspan='5'>No data available.</td></tr>"
        else:
            for row in preview_rows[:100]:
                ap, bp = row.get('ap', ''), row.get('bp', '')
                ad = f"<b>{alias_db[ap]} [{ap}]</b>" if ap in alias_db else ap
                bd = f"<b>{alias_db[bp]} [{bp}]</b>" if bp in alias_db else bp
                logs_html += f"<tr><td>{row.get('dt','')}</td><td>{ad}</td><td>{bd}</td><td>{row.get('freq','')}</td><td>{row.get('loc','')}</td></tr>"
        logs_html += "</tbody></table>"

        # --- SECTION 5: SPATIAL ---
        spatial_html = ""
        try:
            sl_data = json.loads(str(same_loc_json)) if same_loc_json else []
            if isinstance(sl_data, str): sl_data = json.loads(sl_data)
        except:
            sl_data = []

        if not sl_data:
            spatial_html = "<p><i>No concurrent target location overlaps detected.</i></p>"
        else:
            spatial_html = "<table class=\"forensic-table\"><thead><tr><th>Time</th><th>Ap</th><th>Bp</th><th>LAC</th><th>Cell</th><th>Address</th><th>Reason</th></tr></thead><tbody>"
            for row in sl_data[:500]:
                ap, bp = row.get('A_Party', ''), row.get('B_Party', '')
                ad = f"<b>{alias_db[ap]} [{ap}]</b>" if ap in alias_db else ap
                bd = f"<b>{alias_db[bp]} [{bp}]</b>" if bp in alias_db else bp
                spatial_html += f"<tr><td>{row.get('Time','')}</td><td>{ad}</td><td>{bd}</td><td>{row.get('LAC','')}</td><td>{row.get('Cell','')}</td><td>{row.get('BTS_Loc','')}</td><td>{row.get('Reason','')}</td></tr>"
            spatial_html += "</tbody></table>"

        # --- HTML FINAL ASSEMBLY ---
        html = f"""
        <html>
        <head>
            <style>
                body {{ font-family: Arial, sans-serif; font-size: 11pt; margin: 0.5in; line-height: 1.4; color: #000; }}
                .classification {{ text-align: center; font-weight: bold; margin-bottom: 10pt; text-transform: uppercase; }}
                .header {{ text-align: center; border-bottom: 2pt solid #000; padding-bottom: 10pt; margin-bottom: 20pt; }}
                h1 {{ font-size: 15pt; border-bottom: 1pt solid #000; margin-top: 20pt; text-transform: uppercase; }}
                h2 {{ font-size: 12pt; margin-top: 15pt; text-transform: uppercase; }}
                .forensic-table {{ width: 100%; border-collapse: collapse; margin-top: 10pt; }}
                .forensic-table th, .forensic-table td {{ border: 0.5pt solid #000; padding: 4pt; text-align: left; font-size: 9pt; word-wrap: break-word; }}
                .forensic-table th {{ background-color: #f0f0f0; }}
                .footer {{ text-align: center; font-size: 8pt; margin-top: 30pt; border-top: 1pt dashed #ccc; padding-top: 10pt; }}
                .crisp-summary {{ font-size: 11pt; margin-bottom: 15pt; white-space: pre-wrap; }}
            </style>
        </head>
        <body>
            <div class="classification">SECRET</div>
            <div class="header">
                <h1 style="border:none; margin:0;">📡 CDR TELEMETRY INTELLIGENCE BRIEF</h1>
                <div style="font-weight:bold; font-size:13pt; margin-top:5pt;">CASE: {str(case_name).upper()}</div>
                <div style="font-size:9pt;">Generated: {time.strftime('%Y-%m-%d %H:%M:%S')}</div>
            </div>
            <div class="metadata">
                <b>Location:</b> {str(location_request)} | <b>Timeline:</b> {str(timeline_analysis)}<br/>
                <b>Sources:</b> {", ".join(py_cdrs)}
            </div>
            <h1>🎯 Operational Summary Matrix</h1>
            {format_bold_aliases(summary_html)}
            <h1>🧬 Link Correlation Insights</h1>
            {link_analysis_html}
            <h1>📱 Subscriber Device Profiles</h1>
            {device_html if device_html else "<p>No hardware signatures detected.</p>"}
            <h1>👁️ Telemetry Logs Preview</h1>
            {logs_html}
            <h1>📍 Spatial Cross-Over Interceptions</h1>
            <div class="crisp-summary">
                <b>Summary Matrix:</b><br/>
                {format_bold_aliases(spatial_summary) if spatial_summary else "No spatial overlaps identified."}
            </div>
            {spatial_html}
            <div class="footer">*** END OF BRIEF ***</div>
            <div class="classification">SECRET</div>
        </body>
        </html>
        """
        return html
    except Exception as e:
        return f"<html><body>Error generating report: {str(e)}</body></html>"
