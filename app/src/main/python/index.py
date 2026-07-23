"""
Offline Forensic Intelligence Engine (Android Optimized - Disk-Backed Cache Edition)
Refactored for performance, stability, and zero-RAM bloat.
"""
import os
import json
import time
import pandas as pd
from thefuzz import fuzz
import pdf_converter 

# Global cache container for the TAC hardware database
TAC_DB = None

def _get_cache_path(output_dir, filename):
    """Generates a secure, persistent path inside a local cache directory within the output folder."""
    cache_dir = os.path.join(output_dir, ".cache_engine")
    if not os.path.exists(cache_dir):
        os.makedirs(cache_dir, exist_ok=True)
    return os.path.join(cache_dir, filename)

def load_aliases():
    try:
        path = "/storage/emulated/0/Documents/CDR_Reports/aliases_metadata.json"
        if os.path.exists(path):
            with open(path, 'r', encoding='utf-8') as f:
                return json.load(f)
    except: pass
    return {}

def format_with_alias(val, alias_map):
    s_val = str(val).strip()
    if s_val in alias_map:
        return f"{s_val} (📌 {alias_map[s_val]})"
    return s_val

def lookup_imei(imei):
    global TAC_DB
    if not imei or len(str(imei)) < 8: return None
    try:
        if TAC_DB is None:
            db_path = os.path.join(os.path.dirname(__file__), "tac_db.csv")
            if os.path.exists(db_path):
                TAC_DB = pd.read_csv(db_path, dtype=str)
                TAC_DB['TAC'] = TAC_DB['TAC'].str.strip()
            else: return None
        
        match = TAC_DB[TAC_DB['TAC'] == str(imei)[:8]]
        if not match.empty:
            return f"{match.iloc[0]['Manufacturer']} {match.iloc[0]['Model']}"
    except: pass
    return None

def process_cdr_data(file_paths, intended_location, output_dir, start_ts=None, end_ts=None):
    """Parses CDRs, exports Excel, and caches heavy data objects to disk."""
    try:
        if not file_paths: return {"status": "error", "message": "No files selected."}
        alias_map = load_aliases()
        all_dfs = []
        is_single_file = (len(list(file_paths)) == 1)

        for path in file_paths:
            if not os.path.exists(path): continue
            try:
                if path.lower().endswith('.pdf'):
                    df = pdf_converter.pdf_to_dataframe(path)
                else:
                    df = pd.read_excel(path, engine="openpyxl", dtype=str)
                
                if not df.empty and len(df.columns) >= 12:
                    all_dfs.append(df)
            except: continue

        if not all_dfs: return {"status": "error", "message": "No readable data detected."}
        
        combined_df = pd.concat(all_dfs, ignore_index=True)
        col_A, col_C, col_D, col_E, col_H, col_I, col_J, col_L = combined_df.columns[0], combined_df.columns[2], combined_df.columns[3], combined_df.columns[4], combined_df.columns[7], combined_df.columns[8], combined_df.columns[9], combined_df.columns[11]

        # 1. Vectorized Sanitization
        combined_df[col_C] = combined_df[col_C].astype(str).str.replace(r'^\+?88|\D', '', regex=True).str[-11:]
        combined_df[col_D] = combined_df[col_D].astype(str).str.replace(r'^\+?88|\D', '', regex=True).str[-11:]
        
        for col in [col_H, col_I, col_L, col_J]: 
            combined_df[col] = combined_df[col].astype(str).str.strip().replace(['nan', 'NaN', 'None', ''], '--Empty--')
            if col in [col_H, col_I, col_J]:
                combined_df[col] = combined_df[col].apply(lambda x: str(x).split('.')[0] if x != '--Empty--' else '--Empty--')

        combined_df['_parsed_dt'] = pd.to_datetime(combined_df[col_A], errors='coerce')
        combined_df = combined_df.dropna(subset=['_parsed_dt'])

        if start_ts and end_ts:
            mask = (combined_df['_parsed_dt'] >= pd.to_datetime(start_ts)) & (combined_df['_parsed_dt'] <= pd.to_datetime(end_ts))
            combined_df = combined_df[mask]

        if combined_df.empty: return {"status": "error", "message": "No data in selected timeline."}

        # 2. Hardware Analysis
        unique_a_parties = combined_df[combined_df[col_C].str.len() == 11][col_C].unique().tolist()
        summary_a_parties_str = ", ".join([format_with_alias(a, alias_map) for a in unique_a_parties])

        raw_imei_clean = combined_df[(combined_df[col_J] != '--Empty--') & (combined_df[col_C].str.len() == 11)]
        target_imei_counts = raw_imei_clean.groupby(col_C)[col_J].nunique().to_dict()
        true_swapped = [f"{format_with_alias(n, alias_map)} ({c} profiles)" for n, c in target_imei_counts.items() if c >= 3]
        summary_imei_swappers_str = f"IMEI Swappers: {', '.join(true_swapped)}" if true_swapped else "Hardware Stability: Normal"

        imei_sim_mapping = raw_imei_clean.groupby(col_J)[col_C].nunique().to_dict()
        true_multi = [f"Handset {format_with_alias(i, alias_map)} ({s} numbers)" for i, s in imei_sim_mapping.items() if s >= 3]
        summary_multi_sim_str = f"Multi-SIM Burners: {', '.join(true_multi)}" if true_multi else "Device Identity: Normal"

        # 3. Spatial Roadmap (Movement Timeline)
        spatial_roadmap_list = []
        roadmap_df = combined_df.sort_values(by='_parsed_dt').reset_index(drop=True)
        loc_signatures = roadmap_df[col_L] + "_" + roadmap_df[col_H] + "_" + roadmap_df[col_I]
        roadmap_df['loc_block'] = (loc_signatures != loc_signatures.shift()).cumsum()
        
        for seq_id, (block_id, block) in enumerate(roadmap_df.groupby('loc_block'), 1):
            arr_time, dep_time = block['_parsed_dt'].min(), block['_parsed_dt'].max()
            delta_sec = int((dep_time - arr_time).total_seconds())
            duration = f"{delta_sec // 3600}h {(delta_sec % 3600) // 60}m" if delta_sec >= 3600 else f"{delta_sec // 60}m"
            spatial_roadmap_list.append({
                "Sequence": f"{seq_id:02d}", "A_Party": format_with_alias(block[col_C].iloc[0], alias_map),
                "Location": str(block[col_L].iloc[0]), "Arrived": arr_time.strftime('%Y-%m-%d %H:%M'),
                "Departed": dep_time.strftime('%Y-%m-%d %H:%M'), "Duration": duration
            })
        
        roadmap_cache = _get_cache_path(output_dir, "spatial_roadmap.json")
        with open(roadmap_cache, "w", encoding="utf-8") as f:
            json.dump(spatial_roadmap_list, f, ensure_ascii=False)

        # 4. Filters & Frequency
        final_condition = pd.Series(True, index=combined_df.index)
        if intended_location:
            keywords = [loc.strip().lower() for loc in intended_location.split(",") if loc.strip()]
            if keywords: final_condition = combined_df[col_L].str.lower().apply(lambda val: any(k in val for k in keywords))

        filtered_df = combined_df[final_condition].copy()
        filtered_df[col_E] = pd.to_numeric(filtered_df[col_E], errors="coerce").fillna(0)
        freq_map = filtered_df[col_D].value_counts()
        filtered_df["Frequency"] = filtered_df[col_D].map(freq_map).fillna(0).astype(int)

        b_to_as = combined_df.groupby(col_D)[col_C].nunique()
        extracted_common = [b for b, count in b_to_as.items() if count > 1 and len(str(b)) >= 5]
        
        summary_common_b_str = ", ".join([format_with_alias(b, alias_map) for b in extracted_common]) if extracted_common else "No shared contacts between different A-parties."

        # 5. Cache Preview Rows
        display_df = filtered_df.drop_duplicates(subset=[col_D]).sort_values(by="Frequency", ascending=False)
        preview_list = [{"dt": row[col_A], "ap": format_with_alias(row[col_C], alias_map), "bp": format_with_alias(row[col_D], alias_map), "freq": str(row["Frequency"]), "loc": str(row[col_L])} for _, row in display_df.head(100).iterrows()]
        preview_cache = _get_cache_path(output_dir, "preview_rows.json")
        with open(preview_cache, "w", encoding="utf-8") as f:
            json.dump(preview_list, f, ensure_ascii=False)

        # 6. Night Routine
        night_df = combined_df[combined_df['_parsed_dt'].dt.hour.isin([22,23,0,1,2,3,4,5])]
        night_stays = " | ".join(night_df[col_L].value_counts().head(3).index.tolist()) if not night_df.empty else "Insufficient Data"
        
        hourly_activity = {}
        for a_p in unique_a_parties:
            dist = combined_df[combined_df[col_C] == a_p]['_parsed_dt'].dt.hour.value_counts().reindex(range(24), fill_value=0).to_dict()
            hourly_activity[a_p] = {str(h): int(v) for h, v in dist.items()}

        # 7. Excel Export
        output_filename = f"CDR_Analysis_{unique_a_parties[0] if unique_a_parties else 'Unknown'}_{int(time.time())}.xlsx"
        output_excel_path = os.path.join(output_dir, output_filename)
        with pd.ExcelWriter(output_excel_path, engine="openpyxl") as writer:
            summary_metrics = pd.DataFrame({
                "Metric": ["A-Parties", "Common Contacts", "IMEI Swaps", "Multi-SIM", "Night Stays"],
                "Intelligence": [summary_a_parties_str, summary_common_b_str, summary_imei_swappers_str, summary_multi_sim_str, night_stays]
            })
            summary_metrics.to_excel(writer, sheet_name="Summary", index=False)
            filtered_df.drop(columns=['_parsed_dt', 'Frequency']).to_excel(writer, sheet_name="Data", index=False)

        # 8. Graph & Report Data (Cached for PDF/UI)
        ap_to_imeis = raw_imei_clean.groupby(col_C)[col_J].unique().apply(list).to_dict()
        
        # Check for Lat/Long columns
        has_lat_long = False
        for c in combined_df.columns:
            low_c = str(c).lower()
            if 'lat' in low_c or 'lon' in low_c or 'lng' in low_c:
                has_lat_long = True
                break
        if not has_lat_long and len(combined_df.columns) > 13:
            has_lat_long = True

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
                        "top_loc": str(area_map.get(item, "Unknown")),
                        "imeis": ap_to_imeis.get(item, []) if entity_col == col_C else []
                    }

        sim_to_imei_map = {str(ap): [{"imei": str(i), "hw": lookup_imei(i) or "Generic"} for i in imeis] for ap, imeis in ap_to_imeis.items()}
        imei_to_sim_map = {str(imei): {"sims": [format_with_alias(a, alias_map) for a in sims], "hardware": lookup_imei(imei) or "Generic Device"} for imei, sims in raw_imei_clean.groupby(col_J)[col_C].unique().apply(list).to_dict().items()}

        sim_to_imei_graph = {"links": [], "nodes": []}
        seen_nodes = set()
        for sim, imeis in ap_to_imeis.items():
            sap = str(sim)
            if sap not in seen_nodes:
                sim_to_imei_graph["nodes"].append({"id": sap, "type": "SIM"})
                seen_nodes.add(sap)
            for imei in imeis:
                simei = str(imei)
                if simei not in seen_nodes:
                    sim_to_imei_graph["nodes"].append({"id": simei, "type": "IMEI", "hw": lookup_imei(imei) or "Generic Device"})
                    seen_nodes.add(simei)
                sim_to_imei_graph["links"].append({"source": sap, "target": simei})

        common_nums = set(extracted_common)
        common_map = combined_df[combined_df[col_D].isin(common_nums)].groupby(col_D)[col_C].apply(lambda x: list(set(x))).to_dict()
        uncommon_map = combined_df[~combined_df[col_D].isin(common_nums)].groupby(col_C)[col_D].apply(lambda x: list(set(x))).to_dict()
        
        area_clusters = [{"area": str(k), "count": int(v)} for k, v in combined_df[col_L].value_counts().head(12).items() if str(k).strip() not in ['', '--Empty--', 'nan', 'NaN']]

        graph_data = {
            "centers": unique_a_parties,
            "preview_rows": preview_list,
            "common-links": [{"target": cb, "source": list(s)} for cb, s in common_map.items()],
            "uncommon-links": [{"source": a, "target-links": t} for a, t in uncommon_map.items()],
            "node_profiles": node_profiles,
            "sim_to_imei_map": sim_to_imei_map,
            "imei_to_sim_map": imei_to_sim_map,
            "sim_to_imei_graph": sim_to_imei_graph,
            "hourly_activity": hourly_activity,
            "area_clusters": area_clusters,
            "all_party_areas": {**a_areas, **b_areas},
            "alias_map": alias_map
        }
        graph_cache = _get_cache_path(output_dir, "graph_data.json")
        with open(graph_cache, "w", encoding="utf-8") as f:
            json.dump(graph_data, f, ensure_ascii=False)

        return {
            "status": "success", 
            "output_path": output_excel_path,
            "roadmap_cache": roadmap_cache,
            "preview_cache": preview_cache,
            "graph_cache": graph_cache,
            "metrics": {
                "a_parties": summary_a_parties_str, 
                "top_b_parties": [{"b_party": format_with_alias(r[col_D], alias_map), "frequency": str(r["Frequency"]), "last_called": r[col_A]} for _, r in display_df.head(10).iterrows()],
                "night_stays": night_stays, 
                "common_b_parties": summary_common_b_str,
                "imei_swappers": summary_imei_swappers_str, 
                "multi_sim": summary_multi_sim_str,
                "night_routine": f"Night Active: {len(night_df)} events",
                "hourly_activity": hourly_activity
            }
        }
    except Exception as e: return {"status": "error", "message": f"Engine Failure: {str(e)}"}

def same_location_analysis(file_paths, output_dir, start_ts=None, end_ts=None, progress_callback=None):
    """Identifies movement overlaps and caches to disk."""
    try:
        alias_map = load_aliases()
        all_data = []
        for p in file_paths:
            if not os.path.exists(p): continue
            try:
                df = pd.read_excel(p, engine="openpyxl", dtype=str)
                if not df.empty and len(df.columns) >= 12:
                    t = pd.DataFrame()
                    t['R'] = pd.to_datetime(df[df.columns[0]], errors='coerce')
                    t['S'] = t['R'].dt.strftime('%Y-%m-%d %H:%M:%S')
                    t['A'] = df[df.columns[2]].astype(str).str.replace(r'^\+?88|\D', '', regex=True).str[-11:]
                    t['B'] = df[df.columns[3]].astype(str).str.replace(r'^\+?88|\D', '', regex=True).str[-11:]
                    t['L'] = df[df.columns[7]].astype(str).str.replace(r'\..*', '', regex=True)
                    t['C'] = df[df.columns[8]].astype(str).str.replace(r'\..*', '', regex=True)
                    t['Loc'] = df[df.columns[11]].fillna('--Empty--').astype(str).str.strip()
                    all_data.append(t[t['A'].str.len() == 11])
            except: continue

        if not all_data: return {"status": "error", "message": "No valid data for overlap analysis."}
        combined = pd.concat(all_data, ignore_index=True).dropna(subset=['R'])
        
        if start_ts and end_ts:
            combined = combined[(combined['R'] >= pd.to_datetime(start_ts)) & (combined['R'] <= pd.to_datetime(end_ts))]

        results = []
        unique_days = sorted(combined['R'].dt.date.unique())
        if not unique_days: return {"status": "success", "data": "[]", "summary": "No data in timeline."}

        for i, d in enumerate(unique_days):
            day_df = combined[combined['R'].dt.date == d]
            if day_df['A'].nunique() < 2: 
                if progress_callback: progress_callback.onProgress(int((i + 1) / len(unique_days) * 100))
                continue
                
            matches = day_df.groupby('L').filter(lambda x: x['A'].nunique() > 1)
            for _, r in matches.iterrows():
                results.append({
                    "Time": r['S'], "A_Party": format_with_alias(r['A'], alias_map), 
                    "B_Party": format_with_alias(r['B'], alias_map), "LAC": r['L'], 
                    "Cell": r['C'], "BTS_Loc": r['Loc'], "Reason": "Tower Match"
                })
            
            # Address Similarity Check (Optimized with thefuzz)
            a_list = sorted(list(day_df['A'].unique()))
            addr_map = day_df[day_df['Loc'] != '--Empty--'].groupby('A')['Loc'].unique().to_dict()
            for i_ap in range(len(a_list)):
                for j_ap in range(i_ap+1, len(a_list)):
                    ap1, ap2 = a_list[i_ap], a_list[j_ap]
                    for ad1 in addr_map.get(ap1, []):
                        for ad2 in addr_map.get(ap2, []):
                            if fuzz.partial_ratio(str(ad1).lower(), str(ad2).lower()) >= 80:
                                rows = day_df[(day_df['A'].isin([ap1, ap2])) & (day_df['Loc'].isin([ad1, ad2]))]
                                for _, r in rows.iterrows():
                                    results.append({"Time": r['S'], "A_Party": format_with_alias(r['A'], alias_map), "B_Party": format_with_alias(r['B'], alias_map), "LAC": r['L'], "Cell": r['C'], "BTS_Loc": r['Loc'], "Reason": "Address Similarity"})

            if progress_callback: progress_callback.onProgress(int((i + 1) / len(unique_days) * 100))

        cache_path = _get_cache_path(output_dir, "overlap_results.json")
        with open(cache_path, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False)
            
        return {"status": "success", "cache_path": cache_path, "count": len(results), "summary": f"Detected {len(results)} spatial overlaps."}
    except Exception as e: return {"status": "error", "message": f"Overlap Analysis Failed: {str(e)}"}

def search_cdr_data(file_paths, search_query):
    # Keep existing search logic but standardize time
    try:
        alias_map = load_aliases()
        terms = [s.strip() for s in str(search_query).split(",") if s.strip()]
        all_dfs = []
        for path in file_paths:
            if not os.path.exists(path): continue
            try:
                df = pd.read_excel(path, engine="openpyxl", dtype=str)
                if df.empty or len(df.columns) < 12: continue
                df['_internal_a'] = df[df.columns[2]].astype(str).str.replace(r'^\+?88|\D', '', regex=True).str[-11:]
                df['_internal_time'] = pd.to_datetime(df[df.columns[0]], errors='coerce')
                df['_internal_loc'] = df[df.columns[11]].fillna('--Empty--').astype(str).str.strip()
                all_dfs.append(df)
            except: continue
            
        if not all_dfs: return {"status": "error", "message": "No data searched."}
        combined = pd.concat(all_dfs, ignore_index=True)
        dialog_lines = []
        for term in terms:
            mask = combined.astype(str).apply(lambda col: col.str.contains(term, case=False, na=False)).any(axis=1)
            match_df = combined[mask]
            if not match_df.empty:
                suspects = sorted(list(set(match_df['_internal_a'].tolist())))
                dialog_lines.append(f"<b>Term: <font color='#3182CE'>{term}</font></b><br/>• Linked Suspects: {', '.join([format_with_alias(s, alias_map) for s in suspects])}<br/>• Hits: {len(match_df)}")
            else: dialog_lines.append(f"<font color='#E53E3E'><b>Term: {term}</b></font><br/>&nbsp;&nbsp;Status: <i>Not Found</i>")
        return {"status": "success", "summary_html": "<br/><br/>".join(dialog_lines)}
    except Exception as e: return {"status": "error", "message": str(e)}

def bring_loc_trail_out(file_paths, start_ts, end_ts, context):
    try:
        if not file_paths: return False
        all_dfs = []
        for path in file_paths:
            if not os.path.exists(path): continue
            try:
                if path.lower().endswith('.pdf'):
                    df = pdf_converter.pdf_to_dataframe(path)
                else:
                    df = pd.read_excel(path, engine="openpyxl", dtype=str)
                if not df.empty: all_dfs.append(df)
            except: continue
        
        if not all_dfs: return False
        
        combined_df = pd.concat(all_dfs, ignore_index=True)
        col_time = combined_df.columns[0]
        col_aparty = combined_df.columns[2]
        
        # Determine Lat/Long columns
        lat_col = None
        lon_col = None
        for c in combined_df.columns:
            low_c = str(c).lower()
            if 'lat' in low_c: lat_col = c
            if 'lon' in low_c or 'lng' in low_c: lon_col = c
            
        if not lat_col or not lon_col:
            if len(combined_df.columns) > 13:
                lat_col = combined_df.columns[12]
                lon_col = combined_df.columns[13]
            else:
                return False

        combined_df[col_time] = pd.to_datetime(combined_df[col_time], errors='coerce')
        if start_ts and end_ts:
            combined_df = combined_df[(combined_df[col_time] >= pd.to_datetime(start_ts)) & (combined_df[col_time] <= pd.to_datetime(end_ts))]
        
        combined_df = combined_df.dropna(subset=[col_time, lat_col, lon_col])
        combined_df = combined_df.sort_values(by=col_time)
        
        result = {}
        alias_map = load_aliases()
        
        for aparty, group in combined_df.groupby(col_aparty):
            points = []
            unique_locs = {}
            saparty = format_with_alias(aparty, alias_map)
            
            for _, row in group.iterrows():
                try:
                    lat = float(row[lat_col])
                    lng = float(row[lon_col])
                    # Format: 15 Oct 23, 14:30
                    time_str = row[col_time].strftime('%d %b %y, %H:%M')
                    
                    points.append({"lat": lat, "lng": lng, "time": time_str})
                    
                    loc_key = (round(lat, 6), round(lng, 6))
                    if loc_key not in unique_locs:
                        unique_locs[loc_key] = {"lat": lat, "lng": lng, "freq": 0, "times": []}
                    unique_locs[loc_key]["freq"] += 1
                    unique_locs[loc_key]["times"].append(time_str)
                except: continue
                
            if points:
                result[saparty] = {
                    "trail": points,
                    "markers": [v for v in unique_locs.values()]
                }
        
        json_str = json.dumps(result, ensure_ascii=False)
        
        # Save to Shared Preferences using Android Context
        prefs = context.getSharedPreferences("LocationTrailPrefs", 0)
        editor = prefs.edit()
        editor.putString("trail_data", json_str)
        editor.apply()
        
        return True
    except Exception as e:
        print(f"Error in bring_loc_trail_out: {e}")
        return False

def export_same_location_to_excel(json_data, output_path):
    try:
        data = json.loads(json_data)
        if not data: return {"status": "error", "message": "No data to export."}
        pd.DataFrame(data).to_excel(output_path, index=False)
        return {"status": "success", "output_path": output_path}
    except Exception as e: return {"status": "error", "message": str(e)}
