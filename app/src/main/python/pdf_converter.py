import pandas as pd
import re

def pdf_to_dataframe(pdf_path):
    """
    Robustly extracts CDR data from PDF tables and maps them to a 13-column format.
    """
    import pdfplumber
    import pandas as pd
    import re
    
    extracted_rows = []
    empty_val = "--empty@pdf--"
    
    try:
        with pdfplumber.open(pdf_path) as pdf:
            for page in pdf.pages:
                tables = page.extract_tables()
                if not tables: continue
                
                for table in tables:
                    for row in table:
                        if not row: continue
                        # Clean and join to check if it's a header or empty
                        clean_row = [re.sub(r'\s+', ' ', str(c).strip()) if c else "" for c in row]
                        row_text = " ".join(clean_row).lower()
                        
                        if not any(clean_row) or "start" in row_text or "party" in row_text:
                            continue
                        
                        # We need at least Start Time, Party A, and Party B
                        if len(clean_row) >= 3:
                            extracted_rows.append(clean_row)
                            
        if not extracted_rows:
            return pd.DataFrame()

        # Define 13 Standard Columns (A to M)
        standard_columns = [
            "Start", "B_Empty", "Party A", "Party B", "Call Duration", 
            "Usage Type", "Call Type", "Lac ID", "Cell ID", "IMEI", 
            "K_Empty", "Bts Address", "M_Empty"
        ]
        
        normalized_data = []
        for row in extracted_rows:
            # Pad row to at least 10 columns to avoid index errors
            r = row + [""] * (10 - len(row))
            
            # Smart mapping:
            # We assume the first 10 columns provided by the user are in order
            # 0:Start, 1:A, 2:B, 3:Dur, 4:Usage, 5:Type, 6:Lac, 7:Cell, 8:Imei, 9:Loc
            
            # Data Cleaning: Ensure Start is a valid-ish date string
            # If it's just numbers, we try to preserve it
            start_val = r[0]
            
            # Party A and B: Clean any non-digit chars (except +)
            a_party = re.sub(r'[^\d+]', '', r[1])
            b_party = re.sub(r'[^\d+]', '', r[2])
            
            # LAC and Cell: Should be digits
            lac = re.sub(r'[^\d]', '', r[6])
            cell = re.sub(r'[^\d]', '', r[7])

            final_row = [
                start_val, # 0: Start
                empty_val, # 1: B_Empty
                a_party,   # 2: Party A
                b_party,   # 3: Party B
                r[3],      # 4: Duration
                r[4],      # 5: Usage
                r[5],      # 6: Type
                lac,       # 7: Lac
                cell,      # 8: Cell
                r[8],      # 9: IMEI
                empty_val, # 10: K_Empty
                r[9],      # 11: Address
                empty_val  # 12: M_Empty
            ]
            normalized_data.append(final_row)
            
        return pd.DataFrame(normalized_data, columns=standard_columns)

    except Exception:
        return pd.DataFrame()
