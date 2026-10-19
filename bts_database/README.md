# Offline Cell Tower Database (Bangladesh - MCC 470)

This folder contains the offline cellular tower mapping engine for Bangladesh.

### Files
* **`bts_bd.db`**: High-performance SQLite database indexed on `(lac, cell_id)` for instant $O(1)$ coordinate resolution.
* **`download_and_build_bts_db.py`**: Automated script to download, extract, and ingest tower records into `bts_bd.db`.

---

### How to Populate / Update `bts_bd.db`

#### Method A: Download OpenCellID Bangladesh Data (Free)
1. Register for a free account at [OpenCelliD.org](https://opencellid.org) to obtain your **API Access Token**.
2. Run:
   ```bash
   python download_and_build_bts_db.py --token YOUR_OPENCELLID_API_TOKEN
   ```
   *This automatically downloads `470.csv.gz` from OpenCellID and builds the database.*

#### Method B: Using Local Operator Master BTS Sheets (GP, Robi, Banglalink, Teletalk)
If you have an official operator BTS list in Excel (`.xlsx`) or CSV:
1. Place the file inside this `bts_database/` folder.
2. Run:
   ```bash
   python download_and_build_bts_db.py --file YOUR_FILE.xlsx
   ```
   *The script automatically detects `LAC`, `Cell ID`, `Latitude`, and `Longitude` columns and populates the database.*
