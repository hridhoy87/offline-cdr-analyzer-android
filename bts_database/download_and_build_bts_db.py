"""
Offline BTS Database Builder for Bangladesh (MCC 470)
Supports OpenCellID automated download and local Operator BTS Excel/CSV conversion.
"""
import os
import sys
import gzip
import sqlite3
import argparse
import urllib.request
import pandas as pd

DB_FILE = "bts_bd.db"
CSV_GZ_FILE = "470.csv.gz"

def create_database(db_path):
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS towers (
            radio TEXT,
            mcc INTEGER,
            mnc INTEGER,
            lac INTEGER,
            cell_id INTEGER,
            unit INTEGER,
            lon REAL,
            lat REAL,
            range INTEGER,
            samples INTEGER,
            address TEXT
        );
    """)
    cur.execute("CREATE INDEX IF NOT EXISTS idx_towers_lac_cell ON towers(lac, cell_id);")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_towers_coords ON towers(lat, lon);")
    conn.commit()
    return conn

def download_opencellid_bd(token, output_path):
    url = f"https://download.unwiredlabs.com/ocid/downloads?token={token}&file={CSV_GZ_FILE}"
    print(f"[*] Downloading OpenCellID Bangladesh data (MCC 470)...")
    print(f"[*] Requesting: {url}")
    
    def reporthook(block_num, block_size, total_size):
        if total_size > 0:
            downloaded = block_num * block_size
            pct = min(100.0, downloaded * 100.0 / total_size)
            sys.stdout.write(f"\rDownloading: {downloaded / (1024*1024):.2f} MB / {total_size / (1024*1024):.2f} MB ({pct:.1f}%)")
            sys.stdout.flush()

    urllib.request.urlretrieve(url, output_path, reporthook=reporthook)
    print("\n[+] Download completed successfully.")

def ingest_opencellid_csv(csv_path, conn):
    print(f"[*] Parsing OpenCellID dataset: {csv_path}...")
    # OpenCellID columns: radio, mcc, net (mnc), area (lac), cell (cell_id), unit, lon, lat, range, samples, changeable, created, updated, averageSignal
    chunk_size = 50000
    total_rows = 0
    
    is_gzip = csv_path.endswith(".gz")
    reader = pd.read_csv(csv_path, compression='gzip' if is_gzip else None, chunksize=chunk_size, dtype={
        'radio': str, 'mcc': int, 'net': int, 'area': int, 'cell': int, 'unit': 'Int64',
        'lon': float, 'lat': float, 'range': 'Int64', 'samples': 'Int64'
    })
    
    for chunk in reader:
        # Standardize column names
        chunk = chunk.rename(columns={'net': 'mnc', 'area': 'lac', 'cell': 'cell_id'})
        cols = ['radio', 'mcc', 'mnc', 'lac', 'cell_id', 'unit', 'lon', 'lat', 'range', 'samples']
        valid_cols = [c for c in cols if c in chunk.columns]
        chunk[valid_cols].to_sql('towers', conn, if_exists='append', index=False)
        total_rows += len(chunk)
        print(f"    -> Ingested {total_rows:,} tower records...")
        
    print(f"[+] Total records ingested: {total_rows:,}")

def ingest_operator_excel(excel_path, conn):
    print(f"[*] Parsing operator BTS Excel: {excel_path}...")
    df = pd.read_excel(excel_path, dtype=str)
    # Flexible column matching
    cols = {c.lower().strip(): c for c in df.columns}
    
    lac_col = next((cols[k] for k in cols if 'lac' in k), None)
    cell_col = next((cols[k] for k in cols if 'cell' in k or 'ci' in k), None)
    lat_col = next((cols[k] for k in cols if 'lat' in k), None)
    lon_col = next((cols[k] for k in cols if 'lon' in k or 'long' in k), None)
    addr_col = next((cols[k] for k in cols if 'addr' in k or 'loc' in k or 'site' in k), None)
    
    if not (lac_col and cell_col and lat_col and lon_col):
        print(f"[!] Error: Could not automatically identify LAC/Cell/Lat/Lon columns in {excel_path}.")
        return

    records = pd.DataFrame()
    records['lac'] = pd.to_numeric(df[lac_col], errors='coerce').fillna(0).astype(int)
    records['cell_id'] = pd.to_numeric(df[cell_col], errors='coerce').fillna(0).astype(int)
    records['lat'] = pd.to_numeric(df[lat_col], errors='coerce')
    records['lon'] = pd.to_numeric(df[lon_col], errors='coerce')
    records['address'] = df[addr_col].astype(str) if addr_col else ""
    records['mcc'] = 470
    
    records = records.dropna(subset=['lat', 'lon'])
    records.to_sql('towers', conn, if_exists='append', index=False)
    print(f"[+] Ingested {len(records):,} operator towers.")

def main():
    parser = argparse.ArgumentParser(description="Build Bangladesh Offline BTS SQLite Database")
    parser.add_argument("--token", help="OpenCellID API token to download 470.csv.gz automatically")
    parser.add_argument("--file", help="Path to existing 470.csv, 470.csv.gz, or operator BTS .xlsx")
    args = parser.parse_args()

    base_dir = os.path.dirname(os.path.abspath(__file__))
    db_path = os.path.join(base_dir, DB_FILE)
    conn = create_database(db_path)

    # 1. Check if token provided to download
    if args.token:
        gz_target = os.path.join(base_dir, CSV_GZ_FILE)
        download_opencellid_bd(args.token, gz_target)
        ingest_opencellid_csv(gz_target, conn)
    # 2. Check if a local file was passed or exists in folder
    elif args.file and os.path.exists(args.file):
        if args.file.endswith(('.xlsx', '.xls')):
            ingest_operator_excel(args.file, conn)
        else:
            ingest_opencellid_csv(args.file, conn)
    else:
        # Check if 470.csv.gz or 470.csv already sitting in folder
        local_gz = os.path.join(base_dir, CSV_GZ_FILE)
        local_csv = os.path.join(base_dir, "470.csv")
        if os.path.exists(local_gz):
            ingest_opencellid_csv(local_gz, conn)
        elif os.path.exists(local_csv):
            ingest_opencellid_csv(local_csv, conn)
        else:
            print("[*] No data file or token specified.")
            print("[*] Usage: python download_and_build_bts_db.py --token <OPENCELLID_API_TOKEN>")
            print("[*] Or place '470.csv.gz' / '470.csv' / operator Excel directly in this directory and run again.")

    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM towers;")
    count = cur.fetchone()[0]
    print(f"\n[✔] Database ready at: {db_path} (Total towers: {count:,})")
    conn.close()

if __name__ == "__main__":
    main()
