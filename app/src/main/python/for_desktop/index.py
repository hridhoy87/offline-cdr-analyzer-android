"""
Offline Forensic Intelligence Engine (Disk-Backed Cache Edition)
Optimized for performance, zero-RAM bloat, and stable PDF generation.
"""
import os
import sys
import json
import time
import shutil
import pandas as pd
from thefuzz import fuzz

# Global cache container for the Type Allocation Code hardware database
TAC_DB = None

def _get_cache_path(filename):
    """Generates a secure, persistent path inside a local cache directory."""
    cache_dir = os.path.join(os.path.expanduser("~"), ".cdr_analyzer_cache")
    os.makedirs(cache_dir, exist_ok=True)
    return os.path.join(cache_dir, filename)

def clear_workspace_cache():
    """Flushes temporary cache files to maintain system hygiene."""
    cache_dir = os.path.join(os.path.expanduser("~"), ".cdr_analyzer_cache")
    if os.path.exists(cache_dir):
        shutil.rmtree(cache_dir)

def lookup_imei(imei):
    """Resolves manufacturer and device model specifications from the TAC database."""
    global TAC_DB
    if not imei or len(str(imei)) < 8: return None
    try:
        if TAC_DB is None:
            base_path = sys._MEIPASS if hasattr(sys, '_MEIPASS') else os.path.dirname(__file__)
            db_path = os.path.join(base_path, "tac_db.csv")
            if os.path.exists(db_path):
                TAC_DB = pd.read_csv(db_path, dtype=str)
                TAC_DB['TAC'] = TAC_DB['TAC'].str.strip()
            else: return None
        
        match = TAC_DB[TAC_DB['TAC'] == str(imei)[:8]]
        if not match.empty:
            return f"{match.iloc[0]['Manufacturer']} {match.iloc[0]['Model']}"
    except: pass
    return None

def _clean_phone_number(val):
    val_str = str(val).strip().split('.')[0]
    if val_str.startswith('+88'): val_str = val_str[3:]
    elif val_str.startswith('88'): val_str = val_str[2:]
    return "".join(c for c in val_str if c.isdigit())

def group_location_by_date_by_CDR(file_paths, start_ts=None, end_ts=None):
    """Groups location data chronologically and caches output directly to disk."""
    try:
        if not file_paths: return {"status": "error", "message": "No data source files selected."}

        all_dfs = []
        for path in file_paths:
            if not os.path.exists(path): continue
            try:
                df = pd.read_excel(path, engine="calamine", dtype=str)
                if not df.empty and len(df.columns) >= 12:
                    df = df.dropna(subset=[df.columns[0], df.columns[2]], how='all')
                    all_dfs.append(df)
            except: continue

        if not all_dfs: return {"status": "error", "message": "No readable log sheets detected."}

        combined_df = pd.concat(all_dfs, ignore_index=True).fillna("--Empty--")
        col_A, col_C, col_H, col_I, col_L = (combined_df.columns[0], combined_df.columns[2], combined_df.columns[7], combined_df.columns[8], combined_df.columns[11])

        combined_df[col_C] = combined_df[col_C].astype(str).str.replace(r'^\+?88|\D', '', regex=True)
        combined_df = combined_df[combined_df[col_C] != '']

        combined_df['_parsed_dt'] = pd.to_datetime(combined_df[col_A], errors='coerce')
        combined_df = combined_df.dropna(subset=['_parsed_dt'])

        if start_ts and end_ts:
            mask = (combined_df['_parsed_dt'] >= pd.to_datetime(start_ts)) & (combined_df['_parsed_dt'] <= pd.to_datetime(end_ts))
            combined_df = combined_df[mask]

        if combined_df.empty: return {"status": "success", "cache_path": None}

        combined_df = combined_df.sort_values(by='_parsed_dt').reset_index(drop=True)
        combined_df['_date_str'] = combined_df['_parsed_dt'].dt.strftime('%d-%m-%y')
        combined_df['_time_str'] = combined_df['_parsed_dt'].dt.strftime('%H:%M')
        combined_df['loc_sig'] = combined_df[col_H].astype(str) + "_" + combined_df[col_I].astype(str) + "_" + combined_df[col_L].astype(str)

        nested_output = []
        for date_val, date_group in combined_df.groupby('_date_str', sort=False):
            date_node = {"date": str(date_val), "data": []}
            for cdr_val, cdr_group in date_group.groupby(col_C, sort=False):
                cdr_node = {"cdr": str(cdr_val), "loc-data": []}
                cdr_group = cdr_group.copy()
                cdr_group['block'] = (cdr_group['loc_sig'] != cdr_group['loc_sig'].shift()).cumsum()
                
                for _, block_data in cdr_group.groupby('block', sort=False):
                    first_row = block_data.iloc[0]
                    cdr_node["loc-data"].append({
                        "start_time": block_data['_time_str'].iloc[0], "end_time": block_data['_time_str'].iloc[-1],
                        "lac": str(first_row[col_H]).split('.')[0], "cell": str(first_row[col_I]).split('.')[0], "address": str(first_row[col_L])
                    })
                date_node["data"].append(cdr_node)
            nested_output.append(date_node)

        # 💡 LAZY LOAD: Write to Cache File instead of passing huge memory blocks
        cache_path = _get_cache_path("grouped_locations.json")
        with open(cache_path, "w", encoding="utf-8") as f:
            json.dump(nested_output, f, ensure_ascii=False)

        return {"status": "success", "cache_path": cache_path}
    except Exception as e: return {"status": "error", "message": f"Location Aggregator Failure: {str(e)}"}

def process_cdr_data(file_paths, intended_location, output_dir, start_ts=None, end_ts=None, progress_callback=None):
    """Parses CDRs, exports Excel, caches Parquet data to disk, and computes metrics."""
    try:
        if not file_paths: return {"status": "error", "message": "No files selected."}
        if progress_callback: progress_callback(15, "Ingesting worksheet data arrays...")
        
        all_dataframes = []
        is_single_file = (len(list(file_paths)) == 1)

        for path in file_paths:
            if not os.path.exists(path): continue
            try:
                df = pd.read_excel(path, engine="calamine", dtype=str)
                if not df.empty and len(df.columns) >= 12:
                    df = df.dropna(subset=[df.columns[0], df.columns[2]], how='all')
                    all_dataframes.append(df)
            except: continue

        if not all_dataframes: return {"status": "error", "message": "No readable data found."}
        combined_df = pd.concat(all_dataframes, ignore_index=True).fillna("--Empty--")
        combined_df = combined_df.replace(['nan', 'NaN', 'None', ''], "--Empty--")
        
        col_A, col_B, col_C, col_D, col_E, col_F, col_G, col_H, col_I, col_J, col_K, col_L = combined_df.columns[:12]

        if start_ts and end_ts:
            temp_time = pd.to_datetime(combined_df[col_A], errors='coerce')
            combined_df = combined_df[(temp_time >= pd.to_datetime(start_ts)) & (temp_time <= pd.to_datetime(end_ts))]
            if combined_df.empty: return {"status": "error", "message": "No data in selected timeline."}

        if progress_callback: progress_callback(35, "Sanitizing cellular parameters...")
        raw_c_values = combined_df[col_C].astype(str)
        raw_d_values = combined_df[col_D].astype(str)

        combined_df[col_C] = raw_c_values.str.replace(r'^\+?88|\D', '', regex=True)
        combined_df[col_D] = raw_d_values.str.replace(r'^\+?88|\D', '', regex=True)
        
        for col in [col_B, col_F, col_G, col_H, col_I, col_K, col_L, col_J]: 
            combined_df[col] = combined_df[col].astype(str).str.strip().replace(['nan', 'NaN', 'None', ''], '--Empty--')
        for col in [col_H, col_I, col_J]: 
            combined_df[col] = combined_df[col].apply(lambda x: str(x).split('.')[0] if x != '--Empty--' else '--Empty--')

        mask_c_valid = combined_df[col_C].str.len() == 11
        mask_d_valid = combined_df[col_D].str.len() == 11

        combined_df.loc[~mask_c_valid, col_C] = raw_c_values[~mask_c_valid] + "--[not a mobile number]"
        combined_df.loc[~mask_d_valid, col_D] = raw_d_values[~mask_d_valid] + "--[not a mobile number]"

        combined_df['_parsed_dt'] = pd.to_datetime(combined_df[col_A], errors='coerce')
        unique_a_parties = combined_df.loc[mask_c_valid, col_C].unique().tolist()
        summary_a_parties_str = ", ".join(unique_a_parties)

        if progress_callback: progress_callback(55, "Scanning hardware registries...")
        raw_imei_clean = combined_df[(combined_df[col_J] != '--Empty--') & (combined_df[col_C] != '--Empty--')]
        unique_pairs = raw_imei_clean[[col_C, col_J]].drop_duplicates()
        sim_to_imeis_accumulator = unique_pairs.groupby(col_C)[col_J].apply(list).to_dict()
        imei_to_sims_accumulator = unique_pairs.groupby(col_J)[col_C].apply(list).to_dict()

        true_swapped = []
        for sim, imeis in sim_to_imeis_accumulator.items():
            if len(imeis) >= 3: true_swapped.append(f"{sim} ({len(imeis)} profiles)")
            elif len(imeis) == 2:
                brand1 = str(lookup_imei(imeis[0]) or "unknown1").split()[0].lower()
                brand2 = str(lookup_imei(imeis[1]) or "unknown2").split()[0].lower()
                if brand1 != brand2 and "unknown" not in (brand1 + brand2):
                    true_swapped.append(f"{sim} (Device Switch: {brand1} ➔ {brand2})")

        summary_imei_swappers_str = f"IMEI Swappers: {', '.join(true_swapped)}" if true_swapped else "Hardware Stability: No device swapping observed."
        true_multi = [f"Handset {i} ({len(s)} numbers)" for i, s in imei_to_sims_accumulator.items() if len(s) >= 3]
        summary_multi_sim_str = f"Multi-SIM Burners: {', '.join(true_multi)}" if true_multi else "Device Identity: No multi-SIM handset anomalies."

        if progress_callback: progress_callback(75, "Grouping chronological routines...")
        night_df = combined_df[combined_df['_parsed_dt'].dt.hour.isin([18,19,20,21,22,23,0,1,2,3,4,5])]
        total_night_count = len(night_df)
        deep_night_count = len(night_df[night_df['_parsed_dt'].dt.hour.isin([1,2,3,4])])

        night_stays_list = []
        if total_night_count > 0:
            top_addrs = night_df[col_L].value_counts().head(5).index
            for addr_text in top_addrs:
                matching = night_df[night_df[col_L] == addr_text]
                tower_groups = matching.groupby([col_H, col_I]).size().reset_index(name='count')
                if not tower_groups.empty:
                    tower = tower_groups.sort_values(by='count', ascending=False).iloc[0]
                    night_stays_list.append(f"{addr_text} [LAC: {tower[col_H]}, Cell: {tower[col_I]}]")

        summary_night_stays_str = " | ".join(night_stays_list) if night_stays_list else "Insufficient Data"
        summary_night_routine_str = f"Critical Windows: {round((deep_night_count / total_night_count) * 100, 1)}% of night actions occurred between 01:00 AM and 04:00 AM." if total_night_count > 0 else "Critical Windows: 0%"

        spatial_roadmap_list = []
        roadmap_df = combined_df.dropna(subset=['_parsed_dt']).sort_values(by='_parsed_dt').reset_index(drop=True)
        if not roadmap_df.empty:
            loc_signatures = roadmap_df[col_L] + "_L_" + roadmap_df[col_H] + "_C_" + roadmap_df[col_I]
            roadmap_df['loc_block'] = (loc_signatures != loc_signatures.shift()).cumsum()
            
            sequence_id = 1
            for _, block in roadmap_df.groupby('loc_block'):
                arr_time = block['_parsed_dt'].min()
                dep_time = block['_parsed_dt'].max()
                delta_sec = int((dep_time - arr_time).total_seconds())
                duration_str = f"{delta_sec // 3600}h {(delta_sec % 3600) // 60}m" if delta_sec >= 3600 else (f"{delta_sec // 60} mins" if delta_sec >= 60 else "Instantaneous Transaction")
                spatial_roadmap_list.append({
                    "Sequence": f"{sequence_id:02d}", "A_Party": str(block[col_C].iloc[0]), "B_Party": str(block[col_D].iloc[0]),
                    "Type": str(block[col_B].iloc[0]), "Location": str(block[col_L].iloc[0]), "LAC": str(block[col_H].iloc[0]),
                    "Cell": str(block[col_I].iloc[0]), "Arrived": arr_time.strftime('%Y-%m-%d %H:%M:%S'),
                    "Departed": dep_time.strftime('%Y-%m-%d %H:%M:%S'), "Duration": duration_str
                })
                sequence_id += 1
                
        # 💡 LAZY LOAD: Cache massive roadmap data to disk
        roadmap_cache = _get_cache_path("spatial_roadmap.json")
        with open(roadmap_cache, "w", encoding="utf-8") as f:
            json.dump(spatial_roadmap_list, f, ensure_ascii=False)

        if progress_callback: progress_callback(90, "Writing structural data packages into cache...")
        
        final_condition = pd.Series(True, index=combined_df.index)
        if intended_location:
            keywords = [loc.strip().lower() for loc in intended_location.split(",") if loc.strip()]
            if keywords: 
                final_condition = combined_df[col_L].str.lower().apply(lambda val: any(k in val for k in keywords))

        filtered_df = combined_df[final_condition].copy()
        if filtered_df.empty: return {"status": "error", "message": "No rows matched constraints."}
            
        filtered_df[col_E] = pd.to_numeric(filtered_df[col_E], errors="coerce").fillna(0)
        filtered_df["Frequency"] = filtered_df[col_D].map(filtered_df[col_D].value_counts())
        filtered_df[col_E] = (filtered_df[col_D].map(filtered_df.groupby(col_D)[col_E].sum()) / 60).round(2)

        b_to_as = combined_df.groupby(col_D)[col_C].nunique()
        raw_extracted_common = b_to_as[b_to_as > 1].index.tolist()
        extracted_common = [num for num in raw_extracted_common if len(str(num)) == 11]

        summary_common_b_str = "N/A (Single File)" if is_single_file else (", ".join(extracted_common) if extracted_common else "None")
        if not is_single_file: filtered_df["Common?"] = filtered_df[col_D].apply(lambda x: "Yes" if x in extracted_common else "No")

        filtered_df["Has_Multiple_IMEI"] = filtered_df[col_C].apply(lambda x: "Yes" if len(sim_to_imeis_accumulator.get(x, [])) >= 3 else "No")
        filtered_df = filtered_df.drop_duplicates(subset=[col_D], keep="first")
        filtered_df[col_A] = filtered_df['_parsed_dt'].dt.strftime("%Y-%m-%d %H:%M:%S").fillna("")
        filtered_df = filtered_df.sort_values(by=['_parsed_dt'], ascending=False)

        top_b_data = [{"b_party": str(row[col_D]), "frequency": str(row["Frequency"]), "last_called": str(row[col_A])} for _, row in filtered_df.head(10).iterrows()]
        area_clusters = [{"area": str(k), "count": int(v)} for k, v in combined_df[col_L].value_counts().head(12).items() if str(k).strip() != '']
        preview_data = [{"dt": str(row[col_A]), "ap": str(row[col_C]), "bp": str(row[col_D]), "freq": str(row["Frequency"]), "loc": str(row[col_L])} for _, row in filtered_df.head(50).iterrows()]
        hourly_activity = {a_p: {str(h): int(v) for h, v in combined_df[combined_df[col_C] == a_p]['_parsed_dt'].dt.hour.value_counts().reindex(range(24), fill_value=0).to_dict().items()} for a_p in unique_a_parties}

        # 💡 LAZY LOAD: Cache preview rows
        preview_cache = _get_cache_path("preview_rows.json")
        with open(preview_cache, "w", encoding="utf-8") as f:
            json.dump(preview_data, f, ensure_ascii=False)

        # 1. Output the Standard Excel File
        output_filename = f"{''.join(c for c in (unique_a_parties[0] if unique_a_parties else 'Unknown') if c.isalnum())}-CDR-{time.strftime('%Y%m%d_%H%M%S')}.xlsx"
        output_excel_path = os.path.join(output_dir, output_filename)
        
        vertical_summary = pd.DataFrame({
            "Metric": ["A-Parties", "Top Contacts", "Night Stays", "Common Contacts", "IMEI Swaps", "Multi-SIM", "Critical Windows"],
            "Intelligence": [summary_a_parties_str, ", ".join([i["b_party"] for i in top_b_data]), summary_night_stays_str, summary_common_b_str, summary_imei_swappers_str, summary_multi_sim_str, summary_night_routine_str]
        })

        with pd.ExcelWriter(output_excel_path, engine="xlsxwriter") as writer:
            vertical_summary.to_excel(writer, sheet_name="Summary", index=False)
            filtered_df.drop(columns=['_parsed_dt']).to_excel(writer, sheet_name="Data", index=False)
            workbook = writer.book
            text_format = workbook.add_format({'num_format': '@'})
            writer.sheets['Summary'].set_column(0, len(vertical_summary.columns) - 1, None, text_format)
            writer.sheets['Data'].set_column(0, len(filtered_df.columns) - 1, None, text_format)

        # 💡 LAZY LOAD: Cache Main Parquet
        parquet_cache_path = _get_cache_path("master_data.parquet")
        filtered_df.columns = filtered_df.columns.astype(str)
        filtered_df.drop(columns=['_parsed_dt']).to_parquet(parquet_cache_path, index=False)

        a_areas = combined_df.groupby(col_C)[col_L].agg(lambda x: x.value_counts().index[0] if not x.empty else "Unknown").to_dict()
        b_areas = combined_df.groupby(col_D)[col_L].agg(lambda x: x.value_counts().index[0] if not x.empty else "Unknown").to_dict()
        
        node_profiles = {}
        for entity_col, area_map in [(col_C, a_areas), (col_D, b_areas)]:
            for item in combined_df[entity_col].unique():
                sitem = str(item)
                if sitem and sitem != '--Empty--' and sitem not in node_profiles:
                    sub_df = combined_df[combined_df[entity_col] == item]['_parsed_dt'].dropna()
                    node_profiles[sitem] = {
                        "total": len(combined_df[combined_df[entity_col] == item]),
                        "first": sub_df.min().strftime("%Y-%m-%d %H:%M:%S") if not sub_df.empty else "Unknown",
                        "last": sub_df.max().strftime("%Y-%m-%d %H:%M:%S") if not sub_df.empty else "Unknown",
                        "top_loc": str(area_map.get(item, "Unknown"))
                    }

        detailed_imei_map = {str(imei): {"sims": sims, "hardware": lookup_imei(imei) or "Unknown Device"} for imei, sims in imei_to_sims_accumulator.items() if len(sims) > 1}
        sim_to_imei_map = {str(sim): [{"imei": str(i), "hw": lookup_imei(i) or "Generic Handset"} for i in imeis] for sim, imeis in sim_to_imeis_accumulator.items()}
        
        sim_to_imei_data = {"links": [], "nodes": []}
        seen_nodes = set()
        for sim, imeis in sim_to_imeis_accumulator.items():
            if len(imeis) > 1:
                sap = str(sim)
                if sap not in seen_nodes:
                    sim_to_imei_data["nodes"].append({"id": sap, "type": "SIM"})
                    seen_nodes.add(sap)
                for imei in imeis:
                    simei = str(imei)
                    if simei not in seen_nodes:
                        sim_to_imei_data["nodes"].append({"id": simei, "type": "IMEI", "hw": lookup_imei(imei) or "Generic Handset"})
                        seen_nodes.add(simei)
                    sim_to_imei_data["links"].append({"source": sap, "target": simei})

        common_nums = set(extracted_common) if not is_single_file else set()
        common_map = combined_df[combined_df[col_D].isin(common_nums)].groupby(col_D)[col_C].apply(lambda x: list(set(x))).to_dict()
        uncommon_map = combined_df[~combined_df[col_D].isin(common_nums)].groupby(col_C)[col_D].apply(lambda x: list(set(x))).to_dict()

        # 💡 LAZY LOAD: Cache Graph Data
        graph_dict = {"centers": unique_a_parties, "uncommon-links": [{"source": a, "target-links": t} for a, t in uncommon_map.items()], "common-links": [{"target": cb, "source": list(s)} for cb, s in common_map.items()], "area_clusters": area_clusters, "all_party_areas": {**a_areas, **b_areas}, "node_profiles": node_profiles, "imei_to_sim_map": detailed_imei_map, "sim_to_imei_graph": sim_to_imei_data, "sim_to_imei_map": sim_to_imei_map}
        graph_cache = _get_cache_path("graph_data.json")
        with open(graph_cache, "w", encoding="utf-8") as f:
            json.dump(graph_dict, f, ensure_ascii=False)

        return {
            "status": "success", 
            "output_path": output_excel_path, 
            # We return ONLY small text metrics to update the main.py UI display cleanly without RAM bloat
            "metrics": {
                "a_parties": summary_a_parties_str, "top_b_parties": top_b_data, 
                "night_stays": summary_night_stays_str, "common_b_parties": summary_common_b_str, 
                "imei_swappers": summary_imei_swappers_str, "multi_sim": summary_multi_sim_str, 
                "night_routine": summary_night_routine_str, "hourly_activity": hourly_activity
            }
        }
    except Exception as e: return {"status": "error", "message": f"Engine failure: {str(e)}"}

def export_same_location_to_excel(cache_path_or_payload, output_path):
    """Saves compiled location overlap frames back into a standalone spreadsheet document."""
    try:
        if str(cache_path_or_payload).endswith('.parquet') and os.path.exists(cache_path_or_payload):
            df = pd.read_parquet(cache_path_or_payload)
        else:
            data = json.loads(cache_path_or_payload)
            if not data: return {"status": "error", "message": "No data to export."}
            df = pd.DataFrame(data)

        with pd.ExcelWriter(output_path, engine="xlsxwriter") as writer:
            df.to_excel(writer, index=False, sheet_name="Same Location Analysis")
            writer.sheets["Same Location Analysis"].set_column(0, len(df.columns) - 1, None, writer.book.add_format({'num_format': '@'}))
        return {"status": "success", "output_path": output_path}
    except Exception as e: return {"status": "error", "message": str(e)}

def search_cdr_data(file_paths, search_query):
    # Search logic remains identical, does not need cache optimization because it returns a tiny HTML string
    try:
        if not file_paths: return {"status": "error", "message": "No files provided."}
        terms = [s.strip() for s in str(search_query).split(",") if s.strip()]
        all_dfs = []
        for path in file_paths:
            if not os.path.exists(path): continue
            try:
                df = pd.read_excel(path, engine="calamine", dtype=str)
                if df.empty or len(df.columns) < 12: continue
                a_party = df[df.columns[2]].str.replace(r'\D', '', regex=True).str[-11:].dropna().iloc[-1] if not df.empty else "Unknown"
                df['_internal_a'] = a_party
                df['_internal_time'] = pd.to_datetime(df[df.columns[0]], errors='coerce')
                df['_internal_loc'] = df[df.columns[11]].fillna('--Empty--').astype(str).str.strip()
                all_dfs.append(df)
            except: continue
            
        if not all_dfs: return {"status": "error", "message": "No data extracted."}
        combined = pd.concat(all_dfs, ignore_index=True).fillna('--Empty--')
        dialog_lines = []
        for term in terms:
            mask = combined.astype(str).apply(lambda col: col.str.contains(term, case=False, na=False)).any(axis=1)
            match_df = combined[mask]
            if match_df.empty: dialog_lines.append(f"<font color='#ff5555'><b>Query Target: {term}</b></font><br/>&nbsp;&nbsp;Status: <i>No structural hits inside ledger logs.</i>")
            else:
                hit_cols = [str(col) for col in combined.columns if not str(col).startswith('_internal') and match_df[col].astype(str).str.contains(term, case=False, na=False).any()]
                suspects = sorted(list(set(match_df['_internal_a'].astype(str).tolist())))
                hours = match_df['_internal_time'].dt.hour
                intensity = "Night Activation Heavy" if len(hours[(hours >= 18) | (hours < 6)]) > len(match_df)/2 else "Day Activation Heavy"
                locs = match_df['_internal_loc'][match_df['_internal_loc'] != '--Empty--'].value_counts().head(3)
                res = [
                    f"<b>Forensic Term Hit: <font color='#58a6ff'>{term}</font></b>",
                    f"• Intercepted Suspect Profiles: {', '.join(suspects)}",
                    f"• Vector Match Coordinates: {', '.join(hit_cols[:3])}",
                    f"• Activity Intensity Metrics: {len(match_df)} transaction hits ({intensity})",
                    f"• Top Associated Cluster Hubs: {', '.join(locs.index) if not locs.empty else 'N/A'}"
                ]
                dialog_lines.append("<br/>".join(res))
        return {"status": "success", "summary_html": "<br/><br/>".join(dialog_lines)}
    except Exception as e: return {"status": "error", "message": str(e)}

def crop_cdr_data(file_paths, location_query, start_ts, end_ts, output_dir):
    try:
        all_dfs = [pd.read_excel(p, engine="calamine", dtype=str) for p in file_paths if os.path.exists(p)]
        if not all_dfs: return {"status": "error", "message": "No data available."}
        combined = pd.concat(all_dfs, ignore_index=True).fillna('--Empty--')
        
        temp_time = pd.to_datetime(combined[combined.columns[0]], errors='coerce')
        mask = (temp_time >= pd.to_datetime(start_ts)) & (temp_time <= pd.to_datetime(end_ts))
        if location_query.strip(): mask &= combined[combined.columns[11]].fillna('--Empty--').astype(str).str.contains(location_query, case=False, na=False)
        cropped = combined[mask].copy().astype(str).replace('nan', '--Empty--')
        
        if cropped.empty: return {"status": "error", "message": "No matches found."}
        out_path = os.path.join(output_dir, f"{time.strftime('%Y%m%d_%H%M%S')}_{len(file_paths)}CDRsCropped.xlsx")
        
        with pd.ExcelWriter(out_path, engine="xlsxwriter") as writer:
            cropped.to_excel(writer, index=False)
            writer.sheets['Sheet1'].set_column(0, len(cropped.columns) - 1, None, writer.book.add_format({'num_format': '@'}))
            
        return {"status": "success", "output_path": out_path, "count": len(cropped)}
    except Exception as e: return {"status": "error", "message": str(e)}

def same_location_analysis(file_paths, progress_callback=None, start_ts=None, end_ts=None):
    """Identifies concurrent location overlaps and writes the matrix to a Parquet cache file."""
    try:
        paths = [str(p).strip() for p in file_paths if p and str(p) != 'None' and len(str(p)) > 5]
        if len(paths) < 2: return {"status": "error", "message": f"Need at least 2 CDRs. Found: {len(paths)} valid files."}
            
        all_data = []
        for p in paths:
            if os.path.exists(p):
                df = pd.read_excel(p, engine="calamine", dtype=str)
                if not df.empty and len(df.columns) >= 12:
                    t = pd.DataFrame()
                    t['R'] = pd.to_datetime(df[df.columns[0]], errors='coerce')
                    t['D'] = t['R'].dt.date
                    t['S'] = df[df.columns[0]].fillna('--Empty--').astype(str)
                    t['A'] = df[df.columns[2]].astype(str).str.replace(r'^\+?88|\D', '', regex=True)
                    t['B'] = df[df.columns[3]].astype(str).str.replace(r'^\+?88|\D', '', regex=True)
                    t['L'] = df[df.columns[7]].astype(str).str.replace(r'\..*', '', regex=True).replace(['nan', 'None'], '--Empty--')
                    t['C'] = df[df.columns[8]].astype(str).str.replace(r'\..*', '', regex=True).replace(['nan', 'None'], '--Empty--')
                    t['Loc'] = df[df.columns[11]].fillna('--Empty--').astype(str).str.strip()
                    all_data.append(t)
                    
        if len(all_data) < 2: return {"status": "error", "message": "Insufficient valid data layers."}
        combined = pd.concat(all_data, ignore_index=True).dropna(subset=['D', 'A'])
        results = []

        if start_ts and end_ts:
            combined = combined[(combined['R'] >= pd.to_datetime(start_ts)) & (combined['R'] <= pd.to_datetime(end_ts))]
            if combined.empty: return {"status": "success", "cache_path": None}
        
        unique_days = sorted(combined['D'].unique())
        total_days = len(unique_days)
        
        for i, d in enumerate(unique_days):
            day_df = combined[combined['D'] == d]
            if day_df['A'].nunique() < 2: 
                if progress_callback: progress_callback(int((i + 1) / total_days * 100))
                continue
                
            valid_lac = day_df[(day_df['L'] != '') & (day_df['L'] != '--Empty--')]
            if not valid_lac.empty:
                matches = valid_lac.groupby('L').filter(lambda x: x['A'].nunique() > 1)
                for _, row in matches.iterrows():
                    is_c = day_df[(day_df['L'] == row['L']) & (day_df['C'] == row['C'])]['A'].nunique() > 1
                    results.append({"Time": row['S'], "A_Party": row['A'], "B_Party": row['B'], "LAC": row['L'], "Cell": row['C'], "BTS_Loc": row['Loc'], "Reason": "LAC+Cell Match" if is_c else "LAC Match"})
            
            a_list = sorted(list(day_df['A'].unique()))
            addr_map = day_df[day_df['Loc'] != '--Empty--'].groupby('A')['Loc'].unique().to_dict()
            for i_ap in range(len(a_list)):
                for j_ap in range(i_ap+1, len(a_list)):
                    ap1, ap2 = a_list[i_ap], a_list[j_ap]
                    for ad1 in addr_map.get(ap1, []):
                        for ad2 in addr_map.get(ap2, []):
                            if fuzz.ratio(str(ad1).lower(), str(ad2).lower()) >= 70.0:
                                rows = day_df[(day_df['A'].isin([ap1, ap2])) & (day_df['Loc'].isin([ad1, ad2]))]
                                for _, r in rows.iterrows():
                                    results.append({"Time": r['S'], "A_Party": r['A'], "B_Party": r['B'], "LAC": r['L'], "Cell": r['C'], "BTS_Loc": r['Loc'], "Reason": "Tower Similarity (>70%)"})
            
            if progress_callback: progress_callback(int((i + 1) / total_days * 100))

        if not results: return {"status": "success", "cache_path": None}
            
        final = pd.DataFrame(results).drop_duplicates(subset=['Time', 'A_Party']).sort_values('Time', ascending=False)
        
        # 💡 LAZY LOAD: Write to Parquet Cache
        cache_path = _get_cache_path("overlap_matrix.parquet")
        final.columns = final.columns.astype(str)
        final.to_parquet(cache_path, index=False)
        
        return {"status": "success", "cache_path": cache_path}
    except Exception as e: return {"status": "error", "message": f"Critical Location Error: {str(e)}"}