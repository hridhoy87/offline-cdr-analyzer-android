import io
import os
import sys
import json
import numpy as np
import pandas as pd
from openpyxl.styles import PatternFill, Font, Alignment
import time

def process_cdr_data(file_paths, intended_location, output_dir):
    """
    Offline local library for processing multiple Call Detail Record sheets.
    Saves the final spreadsheet and a summary json metadata file.
    """
    try:
        # file_paths is now passed as a native Python list from Java array
        if not file_paths:
            return {"status": "error", "message": "No files selected."}

        all_dataframes = []
        number_source_map = {}
        # Ensure we have a list for length check
        is_single_file = (len(list(file_paths)) == 1)

        def clean_phone_number(val):
            val_str = str(val).strip().split('.')[0]
            if val_str.startswith('+88'):
                val_str = val_str[3:]
            elif val_str.startswith('88'):
                val_str = val_str[2:]
            
            # Keep only numeric characters
            val_str = "".join(c for c in val_str if c.isdigit())

            if val_str in ['nan', 'None', '']:
                return ''
            return val_str

        for path in file_paths:
            if not os.path.exists(path):
                return {"status": "error", "message": f"File not found on disk: {path}"}
            
            filename = os.path.basename(path)
            try:
                # Use openpyxl explicitly and check for data
                excel_file = pd.ExcelFile(path, engine="openpyxl")
                first_sheet_name = excel_file.sheet_names[0]
                df = excel_file.parse(sheet_name=first_sheet_name)

                if df is not None and not df.empty:
                    # Map filename for cross-reference logic
                    cols = df.columns
                    if len(cols) >= 4:
                        col_D_name = cols[3]
                        df_clean_temp = df[col_D_name].apply(clean_phone_number)
                        unique_numbers_in_file = df_clean_temp.dropna().unique()
                        for num in unique_numbers_in_file:
                            if num and len(num) == 11:
                                if num not in number_source_map:
                                    number_source_map[num] = set()
                                number_source_map[num].add(filename)
                    all_dataframes.append(df)
                else:
                    return {"status": "error", "message": f"Sheet '{first_sheet_name}' in {filename} is empty."}
            except Exception as e:
                return {"status": "error", "message": f"Failed to parse '{filename}': {str(e)}"}

        if not all_dataframes:
            return {"status": "error", "message": f"Found {len(file_paths)} files, but none had readable rows."}

        combined_df = pd.concat(all_dataframes, ignore_index=True)
        combined_df = combined_df.dropna(how='all')

        cols = combined_df.columns
        if len(cols) < 13:
            return {"status": "error", "message": "Structure Error: Requires at least 13 columns."}

        col_A = cols[0]   # Date/Time
        col_C = cols[2]   # A Party Number
        col_D = cols[3]   # B Party Number
        col_E = cols[4]   # Duration
        col_F = cols[5]   # Call Type
        col_H = cols[7]   # LAC
        col_I = cols[8]   # Cell ID
        col_J = cols[9]   # IMEI
        col_L = cols[11]  # Location Address

        combined_df[col_C] = combined_df[col_C].apply(clean_phone_number)
        combined_df[col_D] = combined_df[col_D].apply(clean_phone_number)
        combined_df[col_F] = combined_df[col_F].fillna('').astype(str).str.strip().replace('nan', '')
        combined_df[col_H] = combined_df[col_H].fillna('').astype(str).str.strip().replace('nan', '').apply(lambda x: x.split('.')[0])
        combined_df[col_I] = combined_df[col_I].fillna('').astype(str).str.strip().replace('nan', '').apply(lambda x: x.split('.')[0])
        combined_df[col_L] = combined_df[col_L].fillna('').astype(str).str.strip().replace('nan', '')
        combined_df[col_J] = combined_df[col_J].fillna('').astype(str).str.strip().replace('nan', '').apply(lambda x: x.split('.')[0])

        combined_df = combined_df[(combined_df[col_C].str.len() == 11) & (combined_df[col_D].str.len() == 11)]

        if combined_df.empty:
            return {"status": "error", "message": "0 rows remaining after sorting constraints."}

        def safe_datetime_parser(series):
            if pd.api.types.is_numeric_dtype(series):
                return pd.to_datetime(series, unit='D', origin='1899-12-30', errors='coerce')
            parsed = pd.to_datetime(series, errors='coerce')
            missing_mask = parsed.isna() & (series.astype(str).str.strip() != '')
            if missing_mask.any():
                def parse_element(x):
                    try: return pd.to_datetime(float(x), unit='D', origin='1899-12-30')
                    except ValueError: return pd.to_datetime(x, errors='coerce')
                parsed[missing_mask] = series[missing_mask].apply(parse_element)
            return parsed

        raw_datetime_series = safe_datetime_parser(combined_df[col_A])

        unique_a_parties = [num for num in combined_df[col_C].unique() if num and num != 'nan']
        summary_a_parties_str = ", ".join(unique_a_parties)

        raw_imei_clean = combined_df[(combined_df[col_J] != '') & (combined_df[col_C] != '')]
        
        target_imei_counts = raw_imei_clean.groupby(col_C)[col_J].nunique().to_dict()
        true_swapped_targets = [f"{num} ({cnt} hardware profiles)" for num, cnt in target_imei_counts.items() if cnt >= 3]
        summary_imei_swappers_str = f"IMEI Swappers: {', '.join(true_swapped_targets)}" if true_swapped_targets else "Hardware Stability: No device swapping patterns observed."

        imei_sim_mapping = raw_imei_clean.groupby(col_J)[col_C].nunique().to_dict()
        true_multi_sim_devices = [f"Handset {imei_val} ({sim_cnt} distinct numbers)" for imei_val, sim_cnt in imei_sim_mapping.items() if sim_cnt >= 3]
        summary_multi_sim_str = f"Multi-SIM Burner Hardware: {', '.join(true_multi_sim_devices)}" if true_multi_sim_devices else "Device Identity: No multi-SIM handset anomalies tracked."

        night_stays_list = []
        deep_night_ops_count = 0
        total_valid_times = 0
        raw_night_indices = []

        for idx, ts in enumerate(raw_datetime_series):
            if pd.notnull(ts):
                total_valid_times += 1
                if ts.hour >= 18 or ts.hour < 6:
                    raw_night_indices.append(idx)
                if 1 <= ts.hour <= 4:
                    deep_night_ops_count += 1
        
        if raw_night_indices:
            raw_night_df = combined_df.iloc[raw_night_indices].copy()
            valid_locations = raw_night_df[col_L].astype(str).str.strip()
            valid_locations = valid_locations[valid_locations != '']
            
            if not valid_locations.empty:
                # Get Top 5 locations by frequency
                top_locations = valid_locations.value_counts().head(5)
                
                for i, (addr_text, count) in enumerate(top_locations.items()):
                    matching_records = raw_night_df[raw_night_df[col_L] == addr_text]
                    tower_grouping = matching_records.groupby([col_H, col_I]).size().reset_index(name='count').sort_values(by='count', ascending=False)
                    
                    if not tower_grouping.empty:
                        top_tower = tower_grouping.iloc[0]
                        lac_val, cell_id_val = str(top_tower[col_H]).strip(), str(top_tower[col_I]).strip()
                        lac_info = f"LAC: {lac_val}" if lac_val and lac_val != 'nan' else "LAC: N/A"
                        cell_info = f"Cell ID: {cell_id_val}" if cell_id_val and cell_id_val != 'nan' else "Cell ID: N/A"
                        night_stays_list.append(f"{addr_text} [{lac_info}, {cell_info}]")
        
        # Prepare display string for metrics
        summary_night_stays_str = " | ".join(night_stays_list) if night_stays_list else "Unknown / Insufficient Data"

        deep_night_pct = round((deep_night_ops_count / total_valid_times) * 100, 1) if total_valid_times > 0 else 0
        summary_night_routine_str = f"Deep Night Critical Windows: {deep_night_pct}% of total actions trigger between 01:00 AM and 04:00 AM."

        final_condition = combined_df[col_F] != "SMS-MT"
        if intended_location:
            location_keywords = [loc.strip().lower() for loc in intended_location.split(",") if loc.strip()]
            if location_keywords:
                loc_condition = combined_df[col_L].str.lower().apply(lambda val: any(keyword in val for keyword in location_keywords))
                final_condition = final_condition & loc_condition

        filtered_df = combined_df[final_condition].copy()
        if filtered_df.empty:
            return {"status": "error", "message": "0 rows matched location constraints."}

        filtered_df[col_E] = pd.to_numeric(filtered_df[col_E], errors="coerce").fillna(0)
        frequency_map = filtered_df[col_D].value_counts()
        filtered_df["Frequency"] = filtered_df[col_D].map(frequency_map)

        duration_sum_map = filtered_df.groupby(col_D)[col_E].sum()
        filtered_df[col_E] = filtered_df[col_D].map(duration_sum_map) / 60
        filtered_df[col_E] = filtered_df[col_E].round(2)

        if is_single_file:
            summary_common_b_parties_str = "N/A (Single File Uploaded)"
        else:
            extracted_common_numbers = [num for num, src in number_source_map.items() if len(src) > 1]
            summary_common_b_parties_str = ", ".join(extracted_common_numbers) if extracted_common_numbers else "None"
            filtered_df["Common?"] = filtered_df[col_D].apply(lambda x: "Yes" if x in extracted_common_numbers else "No")

        row_imei_strings = filtered_df[col_J].tolist()
        filtered_df["Has_Multiple_IMEI"] = filtered_df[col_C].apply(lambda x: "Yes" if target_imei_counts.get(x, 0) >= 3 else "No")

        filtered_df = filtered_df.drop_duplicates(subset=[col_D], keep="first")
        filtered_df[col_A] = safe_datetime_parser(filtered_df[col_A])
        filtered_df = filtered_df.sort_values(by=["Frequency", col_A], ascending=[False, False])

        timestamps = filtered_df[col_A].tolist()
        filtered_df[col_A] = filtered_df[col_A].apply(lambda x: x.strftime("%Y-%m-%d %H:%M:%S") if pd.notnull(x) else "")

        top_contacts_list = filtered_df[col_D].head(10).tolist()
        summary_top_three_str = ", ".join(top_contacts_list) if top_contacts_list else "None"

        # Prepare Preview Data for "Take a Peek" table (Top 50 records)
        preview_data = []
        preview_df = filtered_df.head(50)
        for _, row in preview_df.iterrows():
            preview_data.append({
                "dt": str(row[col_A]),
                "bp": str(row[col_D]),
                "freq": str(row["Frequency"]),
                "loc": str(row[col_L])
            })

        original_cols = list(combined_df.columns[0:13])
        address_col_name = original_cols[11]
        original_cols[4] = "Total Call Duration (Mins)"
        
        first_part_cols = original_cols[0:11]
        remaining_cols = [original_cols[12]]
        
        if is_single_file:
            final_cols = first_part_cols + ["Frequency"] + remaining_cols + [address_col_name]
            filtered_df.columns = original_cols + ["Frequency", "Has_Multiple_IMEI"]
            common_status_list = []
        else:
            final_cols = first_part_cols + ["Frequency", "Common?"] + remaining_cols + [address_col_name]
            filtered_df.columns = original_cols + ["Frequency", "Common?", "Has_Multiple_IMEI"]
            common_status_list = filtered_df["Common?"].tolist()
        
        filtered_df = filtered_df[final_cols]
        filtered_df = filtered_df.astype(str).replace('nan', '')

        imei_col_idx = final_cols.index(col_J) + 1
        common_col_idx = final_cols.index("Common?") + 1 if "Common?" in final_cols else -1

        # Dynamic Filename Generation: <AParty>-CDR-Processed-<Date,Time>
        primary_a_party = unique_a_parties[0] if unique_a_parties else "Unknown"
        timestamp_str = time.strftime("%Y%m%d_%H%M%S")
        safe_a_party = "".join(c for c in primary_a_party if c.isalnum())
        output_filename = f"{safe_a_party}-CDR-Processed-{timestamp_str}.xlsx"
        output_excel_path = os.path.join(output_dir, output_filename)

        with pd.ExcelWriter(output_excel_path, engine="openpyxl") as writer:
            vertical_summary_data = {
                "Target Tracking Analytical Metric": [
                    "Target A-Parties Identified", "Primary Target Intercept Vectors (Top Contacts)",
                    "Location of Most Night Stays (1800-0600)", "Common B party numbers (Cross-File Overlaps)",
                    "A-Party Hardware Alteration Status (IMEI Swaps)", "Co-Located Handset Signature Matrix (Multi-SIM IMEIs)",
                    "Deep Night Target Critical Windows (0100-0400)"
                ],
                "Core Intelligence Value / Flag Operational Status": [
                    summary_a_parties_str, summary_top_three_str, summary_night_stays_str,
                    summary_common_b_parties_str, summary_imei_swappers_str, summary_multi_sim_str, summary_night_routine_str
                ]
            }
            pd.DataFrame(vertical_summary_data).to_excel(writer, sheet_name="Intelligence_Summary", index=False)
            
            ws_sum = writer.sheets["Intelligence_Summary"]
            ws_sum.row_dimensions[1].height = 28
            header_fill = PatternFill(start_color="2C3E50", end_color="2C3E50", fill_type="solid")
            header_font = Font(color="FFFFFF", bold=True, size=11)
            
            for col_idx in range(1, 3):
                cell = ws_sum.cell(row=1, column=col_idx)
                cell.fill, cell.font, cell.alignment = header_fill, header_font, Alignment(horizontal="center", vertical="center")

            for row in ws_sum.iter_rows(min_row=2, max_row=8, min_col=1, max_col=2):
                ws_sum.row_dimensions[row[0].row].height = 22
                for cell in row:
                    cell.alignment, cell.font = Alignment(horizontal="left", vertical="center"), Font(size=11)

            filtered_df.to_excel(writer, sheet_name="Combined_Filtered_Data", index=False)
            worksheet = writer.sheets["Combined_Filtered_Data"]
            worksheet.row_dimensions[1].height = 26

            for col_idx in range(1, len(final_cols) + 1):
                header_cell = worksheet.cell(row=1, column=col_idx)
                header_cell.fill, header_cell.font, header_cell.alignment = header_fill, header_font, Alignment(horizontal="center", vertical="center")

            night_fill = PatternFill(start_color="DCE6F1", end_color="DCE6F1", fill_type="solid")
            day_fill = PatternFill(start_color="FFF2CC", end_color="FFF2CC", fill_type="solid")
            common_fill = PatternFill(start_color="10024f", end_color="10024f", fill_type="solid")
            common_font = Font(color="FFD700", bold=True)
            
            palette_pool = [
                {"bg": "E8F8F5", "fg": "117A65"}, {"bg": "FEF9E7", "fg": "B7950B"},
                {"bg": "EBF5FB", "fg": "1F618D"}, {"bg": "F5EEF8", "fg": "6C3483"},
                {"bg": "FBEEE6", "fg": "A04000"}, {"bg": "EAF2F8", "fg": "2471A3"}
            ]
            imei_style_map = {}
            u_idx = 0
            for imei_val in [i for i in row_imei_strings if i != '']:
                if imei_val not in imei_style_map:
                    imei_style_map[imei_val] = palette_pool[u_idx % len(palette_pool)]
                    u_idx += 1

            for row_idx, current_time in enumerate(timestamps, start=2):
                worksheet.row_dimensions[row_idx].height = 20
                is_row_common = (common_status_list[row_idx - 2] == "Yes") if not is_single_file else False
                current_row_imei = row_imei_strings[row_idx - 2]
                
                for col_idx in range(1, len(final_cols) + 1):
                    cell = worksheet.cell(row=row_idx, column=col_idx)
                    cell.number_format = "@"
                    cell.alignment = Alignment(vertical="center")

                    if pd.notnull(current_time):
                        cell.fill = night_fill if (current_time.hour >= 18 or current_time.hour < 6) else day_fill
                    
                    if not is_single_file and is_row_common and col_idx == common_col_idx:
                        cell.fill, cell.font = common_fill, common_font
                    
                    if col_idx == imei_col_idx and current_row_imei in imei_style_map:
                        assigned_style = imei_style_map[current_row_imei]
                        cell.fill = PatternFill(start_color=assigned_style["bg"], end_color=assigned_style["bg"], fill_type="solid")
                        cell.font = Font(color=assigned_style["fg"], bold=True)

            for ws in [ws_sum, worksheet]:
                for col in ws.columns:
                    max_len = max(len(str(cell.value or '')) for cell in col)
                    ws.column_dimensions[col[0].column_letter].width = max(max_len + 4, 12)

        return {
            "status": "success",
            "output_path": output_excel_path,
            "metrics": {
                "a_parties": summary_a_parties_str,
                "top_three": summary_top_three_str,
                "night_stays": summary_night_stays_str,
                "common_b_parties": summary_common_b_parties_str,
                "imei_swappers": summary_imei_swappers_str,
                "multi_sim": summary_multi_sim_str,
                "night_routine": summary_night_routine_str,
                "preview_rows": preview_data
            }
        }

    except Exception as e:
        return {"status": "error", "message": f"Engine calculation failure: {str(e)}"}

if __name__ == "__main__":
    # SeriousPython passes parameters safely through JSON files or basic script strings
    # Read operational instruction inputs from local app configuration JSON files
    try:
        with open("task_input.json", "r") as f:
            task = json.load(f)
        
        result_payload = process_cdr_data(
            file_paths=task["file_paths"],
            intended_location=task["intended_location"],
            output_dir=task["output_dir"]
        )
    except Exception as err:
        result_payload = {"status": "error", "message": f"Lifecycle failure: {str(err)}"}

    # Write output back down to disk file for Dart to instantly read
    with open("task_output.json", "w") as f:
        json.dump(result_payload, f)

    # --- ADD THIS TO PREVENT THE SCRIPT FROM EXITING PREMATURELY ---
    # Keep the process context alive for 5 seconds so Dart finishes file operations
    time.sleep(5)